package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 语音转文字「回传契约」探针 —— <b>只读，不改任何行为</b>。
 *
 * <p>目的（对应 {@code local/VOICE-STT-ANALYSIS.md} §6 的三条未知项）：
 * <ol>
 *   <li>{@code VoiceInputManager$RecognizerCallback} 那 8 个回调（混淆名 a..h）各自的语义
 *       —— 哪个是部分结果、哪个是最终结果、哪个是错误、哪个是音量；</li>
 *   <li>结果对象（回调 {@code h(...)} 的参数，静态分析里叫 {@code aaeo}）的内部结构
 *       —— 将来要自己造一个出来喂给 Gboard 上屏链路；</li>
 *   <li>失败路径：不挂梯子时 S3 网络识别是怎么报错的（哪个枚举、哪个回调）。</li>
 * </ol>
 *
 * <p><b>怎么定位</b>：Gboard 把原始类名/方法名当字符串常量写进日志调用里
 * （{@code "NewS3Recognizer.startRecognition"}、{@code "SpeechRecognitionFactory.java"}），
 * 所以用 DexKit 按<b>字符串锚点</b>找人，再反射枚举它们的全部方法挂钩 ——
 * 不写死 {@code kiy/kmt/kls} 这些混淆名，换版本只需要锚点还在。
 *
 * <p><b>为什么只读</b>：每个钩子都立刻 {@code chain.proceed()} 并把原返回值原样返回，
 * 日志一律包在 try/catch 里，且 {@code toString/equals/hashCode} 不挂钩（防递归）。
 *
 * <p><b>用法</b>：{@code adb shell logcat -s GboardExtVoice}（或本机 {@code su -c logcat}），
 * 然后按 {@code Alt+D} 说一句话。没有梯子时看失败路径，挂了梯子再看成功路径。
 */
final class VoiceProbe {

    /** 探针专用 tag，方便 {@code logcat -s GboardExtVoice} 单独抓。 */
    static final String TAG = "GboardExtVoice";

    /** 开发期开关：语音回传契约探针（只读）。 */
    static final boolean DEV_VOICE_TRACE = false;

    /** 开发期：给每次调用附 6 帧调用栈（判断"谁在调"，很吵，默认关）。 */
    private static final boolean DEV_VOICE_STACK = false;

    /** 单个类方法数超过它就整体跳过（防"锚点其实命中了一个全局工具类"把键盘拖慢）。 */
    private static final int MAX_METHODS_PER_CLASS = 160;

    /** 全局钩子上限，兜底。 */
    private static final int MAX_HOOKS = 600;

    /**
     * 字符串锚点：Gboard 日志里出现的「原始类名.java」或「原始类名.方法名」。
     *
     * <p>每个锚点单独查一次 DexKit（{@code usingStrings} 的数组语义在版本间有歧义，
     * 单个查最稳），结果取并集。
     */
    private static final String[] ANCHORS = {
            // 调度层
            "SpeechRecognitionFacilitator.java",
            "SpeechRecognitionFactory.java",
            // 引擎实现（本机 zh-CN 实际走的那条）
            "NewS3Recognizer.java",
            "NewS3Recognizer.startRecognition",
            "NetworkSpeechRecognizer.java",
            "NetworkSpeechRecognizer.startListening",
            "S3RequestMutator.java",
            "S3HeaderProducer.java",
            // 结果入口
            "SpeechRecognizerListener.java",
            "SpeechRecognizerListener.onRecognitionTerminated",
            // 回调汇聚处（VoiceInputManager$RecognizerCallback 的实现者）
            "VoiceInputManager.startVoiceInput",
            "VoiceInputManager.startRecognizer",
            "VoiceInputManager.resumeRecognition",
            "VoiceInputManager.stopListeningVoice",
            "VoiceInputManager.stopVoiceInput",
            "VoiceInputManagerWrapper.startVoiceInput",
            // 端上/兜底那两条路（本机不生效，留证）
            "FallbackOnDeviceRecognitionProvider.java",
            "OnDeviceRecognitionProvider.java",
    };

    private static volatile boolean sInstalled;
    private static volatile XposedModule sModule;
    private static volatile DexKitBridge sBridge;
    private static volatile int sHooks;

    private static final Set<String> sSeenClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedMethods = ConcurrentHashMap.newKeySet();
    private static final Set<String> sConstructProbed = ConcurrentHashMap.newKeySet();

    /** 关注类（锚点类 + 回调接口实现者）：只有它们的调用会被打出来。 */
    private static final Set<String> sFocus = ConcurrentHashMap.newKeySet();

    /** 结果类（静态分析里的 aaeo）：只有它做深 dump。 */
    private static final Set<String> sResultClasses = ConcurrentHashMap.newKeySet();

    /** 我们自己打日志期间置位：避免 render()/字段反射间接触发别的钩子造成递归刷屏。 */
    private static final ThreadLocal<Boolean> sBusy = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private VoiceProbe() {
    }

    // ------------------------------------------------------------------ install

    static void install(XposedModule module, ClassLoader cl, Context ctx) {
        if (!DEV_VOICE_TRACE) return;
        if (sInstalled) return;
        sInstalled = true;
        sModule = module;
        try {
            final String apk = ctx.getPackageManager()
                    .getApplicationInfo(BridgeHook.TARGET_PKG, 0).sourceDir;
            if (!ServiceProbe.loadDexKitNative(ctx)) {
                Log.w(TAG, "dexkit native 不可用，探针放弃");
                return;
            }
            sBridge = DexKitBridge.create(apk);
            Log.i(TAG, "install: apk=" + apk + " dexNum=" + sBridge.getDexNum());

            // 1) 锚点 -> 混淆类
            final Map<String, String> found = new LinkedHashMap<>();
            for (String a : ANCHORS) {
                try {
                    final ClassDataList cs = sBridge.findClass(
                            FindClass.create().matcher(ClassMatcher.create().usingStrings(a)));
                    for (ClassData c : cs) found.putIfAbsent(c.getName(), a);
                } catch (Throwable t) {
                    Log.w(TAG, "anchor failed: " + a + " : " + t);
                }
            }
            Log.i(TAG, "anchors -> " + found.size() + " class(es)");
            for (Map.Entry<String, String> e : found.entrySet()) {
                sFocus.add(e.getKey());
                Log.i(TAG, "  A " + e.getKey() + "   <= \"" + e.getValue() + "\"");
            }

            // 1.5) 结构化地找出"结果类"：锚点类的方法签名里，那个方法数 ≥5 的接口
            //      （= 回调接口 RecognizerCallback），它"单对象参数"方法的参数类型就是结果类
            //      （= 静态分析里的 aaeo）。只对这个类做深 dump，避免把日志刷爆。
            discoverResultClasses(cl);

            // 2) 挂锚点类；参数里的接口反查实现者（= 回调汇聚处 / provider）
            for (String name : new ArrayList<>(found.keySet())) {
                hookClass(name, cl);
            }
            Log.i(TAG, "install done: classes=" + sSeenClasses.size() + " hooks=" + sHooks
                    + " resultClasses=" + sResultClasses);
        } catch (Throwable tr) {
            Log.w(TAG, "install failed: " + tr);
        } finally {
            try {
                if (sBridge != null) sBridge.close();
            } catch (Throwable ignored) {
            }
            sBridge = null;
        }
    }

    /** 从锚点类的方法签名里推结果类（不写死混淆名）。 */
    private static void discoverResultClasses(ClassLoader cl) {
        for (String name : new ArrayList<>(sFocus)) {
            final Class<?> c;
            try {
                c = Class.forName(name, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                for (Class<?> p : m.getParameterTypes()) {
                    if (!p.isInterface() || p.getDeclaredMethods().length < 5) continue;
                    for (Method cb : p.getDeclaredMethods()) {
                        final Class<?>[] ps = cb.getParameterTypes();
                        if (ps.length != 1) continue;
                        final Class<?> r = ps[0];
                        if (r.isPrimitive() || r.isEnum() || r.isArray()
                                || CharSequence.class.isAssignableFrom(r)
                                || Collection.class.isAssignableFrom(r)) {
                            continue;
                        }
                        if (sResultClasses.add(r.getName())) {
                            Log.i(TAG, "  R " + r.getName()
                                    + "   <= " + p.getName() + "#" + cb.getName());
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ hooking

    private static void hookClass(String name, ClassLoader cl) {
        if (sHooks >= MAX_HOOKS) return;
        if (!sSeenClasses.add(name)) return;

        final Class<?> c;
        try {
            c = Class.forName(name, false, cl);
        } catch (Throwable t) {
            Log.w(TAG, "  ! class " + name + " : " + t);
            return;
        }

        final Method[] ms = c.getDeclaredMethods();
        Log.i(TAG, "class " + name + " super=" + safe(() -> c.getSuperclass().getName())
                + " methods=" + ms.length + (c.isInterface() ? " (interface)" : ""));
        for (Method m : ms) {
            Log.i(TAG, "    M " + name + "#" + m.getName() + descriptor(m));
        }

        // 只跟"接口"这一层：回调接口（RecognizerCallback，8 个方法）与识别器接口
        // （VoiceImeRecognizer，6 个方法）的实现者 = 真正要看的汇聚处。
        // 具体参数类型（会话参数 / 状态位 / 各种集合）**不再挂钩** ——
        // 实测：把那个噪声也挂上会让日志涨到 1MB/s，把 S3 流拖死（第三轮就是这么黄的）。
        if (!c.isInterface()) {
            final Set<Class<?>> deps = new LinkedHashSet<>();
            for (Method m : ms) {
                for (Class<?> p : m.getParameterTypes()) deps.add(p);
            }
            for (Class<?> p : deps) {
                if (p.isInterface() && p.getDeclaredMethods().length >= 5) hookImplementers(p, cl);
            }
        }
        if (c.isInterface()) {
            hookImplementers(c, cl);
            return;
        }
        if (c.isEnum() || c.isArray() || c.isPrimitive()) return;
        if (ms.length > MAX_METHODS_PER_CLASS) {
            Log.i(TAG, "  (方法数 " + ms.length + " > " + MAX_METHODS_PER_CLASS + "，整体跳过)");
            return;
        }
        for (Method m : ms) installHook(c, m);
    }

    private static void hookImplementers(Class<?> iface, ClassLoader cl) {
        final DexKitBridge b = sBridge;
        if (b == null) return;
        try {
            final ClassDataList cs = b.findClass(FindClass.create().matcher(
                    ClassMatcher.create().addInterface(
                            iface.getName(), StringMatchType.Equals, false)));
            Log.i(TAG, "  interface " + iface.getName() + " -> " + cs.size() + " impl(s)");
            for (ClassData cd : cs) {
                sFocus.add(cd.getName());
                hookClass(cd.getName(), cl);
            }
        } catch (Throwable t) {
            Log.w(TAG, "  impl lookup " + iface.getName() + " failed: " + t);
        }
    }

    private static void installHook(Class<?> cls, Method m) {
        final String nm = m.getName();
        if ("<clinit>".equals(nm)) return;
        // 不挂这三个：render()/字段反射会调到它们，挂了就是递归刷屏
        if ("equals".equals(nm) || "hashCode".equals(nm) || "toString".equals(nm)) return;
        if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) return;

        final String key = cls.getName() + "#" + nm + descriptor(m);
        if (!sHookedMethods.add(key)) return;
        if (sHooks >= MAX_HOOKS) return;

        try {
            m.setAccessible(true);
            sModule.hook(m).intercept(chain -> {
                final List<Object> args;
                try {
                    args = new ArrayList<>(chain.getArgs());
                } catch (Throwable t) {
                    return chain.proceed();
                }
                final String head = callHead(cls, nm, args);

                Object ret = null;
                Throwable err = null;
                try {
                    ret = chain.proceed();
                } catch (Throwable t) {
                    err = t;
                }

                if (Boolean.TRUE.equals(sBusy.get())) {
                    return rethrow(ret, err);
                }
                sBusy.set(Boolean.TRUE);
                try {
                    final StringBuilder sb = new StringBuilder(256).append(head);
                    if (err != null) {
                        sb.append("  !! ").append(err.getClass().getName())
                          .append(": ").append(trim(String.valueOf(err.getMessage()), 120));
                    } else if (m.getReturnType() != void.class) {
                        sb.append("  -> ").append(render(ret));
                    }
                    Log.i(TAG, sb.toString());
                    // 只对"结果类"做深层字段 dump —— 其余对象一行 toString 就够，
                    // 深挖会把日志刷到 1MB/s，反而把要观察的 S3 流拖死。
                    for (Object a : args) {
                        if (a == null) continue;
                        if (sResultClasses.contains(a.getClass().getName())) {
                            dumpTree(a, 3, 0, new int[] { 8 });
                            probeConstruct(a);
                        }
                    }
                    if (ret != null && m.getReturnType() != void.class
                            && !isBoring(ret.getClass())) {
                        dumpTree(ret, 1, 0, new int[] { 3 });
                    }
                    if (DEV_VOICE_STACK) Log.i(TAG, "    " + stack());
                } catch (Throwable ignored) {
                } finally {
                    sBusy.set(Boolean.FALSE);
                }
                return rethrow(ret, err);
            });
            sHooks++;
            Log.i(TAG, "  H " + key);
        } catch (Throwable t) {
            Log.w(TAG, "  hook failed " + key + " : " + t);
        }
    }

    private static Object rethrow(Object ret, Throwable err) throws Throwable {
        if (err != null) throw err;
        return ret;
    }

    // ------------------------------------------------------------------ 渲染

    private static String callHead(Class<?> cls, String method, List<Object> args) {
        final StringBuilder sb = new StringBuilder(192);
        sb.append("V ").append(cls.getName()).append('#').append(method).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(render(args.get(i)));
        }
        return sb.append(')').toString();
    }

    /** 只关心语音子系统里那些"有内容"的对象——避免把 ArrayList/HashMap 也 dump 一遍。 */
    private static boolean isBoring(Class<?> c) {
        final String n = c.getName();
        return c.isPrimitive() || c.isEnum() || c.isArray()
                || CharSequence.class.isAssignableFrom(c)
                || Number.class.isAssignableFrom(c)
                || Boolean.class == c || Character.class == c || Class.class == c
                || n.startsWith("java.") || n.startsWith("android.")
                || n.startsWith("androidx.") || n.startsWith("kotlin.");
    }

    private static String render(Object o) {
        if (o == null) return "null";
        final Class<?> c = o.getClass();
        try {
            if (c.isArray()) return renderArray(o, c);
            if (o instanceof CharSequence) return '"' + trim(o.toString(), 200) + '"';
            if (c.isEnum()) return c.getSimpleName() + '.' + ((Enum<?>) o).name();
            if (o instanceof Class) return "class " + ((Class<?>) o).getName();
            if (o instanceof Collection) {
                final Collection<?> col = (Collection<?>) o;
                final StringBuilder sb = new StringBuilder(c.getSimpleName())
                        .append("[size=").append(col.size());
                int n = 0;
                for (Object e : col) {
                    if (n++ >= 4) { sb.append(", …"); break; }
                    sb.append(", ").append(render(e));
                }
                return sb.append(']').toString();
            }
            if (o instanceof Map) {
                final Map<?, ?> mp = (Map<?, ?>) o;
                final StringBuilder sb = new StringBuilder(c.getSimpleName())
                        .append("[size=").append(mp.size());
                int n = 0;
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    if (n++ >= 4) { sb.append(", …"); break; }
                    sb.append(", ").append(render(e.getKey())).append('=')
                      .append(render(e.getValue()));
                }
                return sb.append(']').toString();
            }
            if (o instanceof Number || o instanceof Boolean || o instanceof Character) {
                return String.valueOf(o);
            }
            final String s = String.valueOf(o);
            return "{" + c.getName() + "} " + trim(s, 240);
        } catch (Throwable t) {
            return "<render失败 " + t + ">";
        }
    }

    private static String renderArray(Object o, Class<?> c) {
        final int len = java.lang.reflect.Array.getLength(o);
        if (c.getComponentType() == byte.class) {
            final byte[] b = (byte[]) o;
            final StringBuilder sb = new StringBuilder("byte[").append(len).append("] ");
            for (int i = 0; i < Math.min(len, 16); i++) {
                sb.append(String.format("%02x", b[i]));
            }
            if (len > 16) sb.append("…");
            return sb.toString();
        }
        final StringBuilder sb = new StringBuilder(c.getSimpleName())
                .append("[len=").append(len);
        for (int i = 0; i < Math.min(len, 3); i++) {
            sb.append(", ").append(render(java.lang.reflect.Array.get(o, i)));
        }
        if (len > 3) sb.append(", …");
        return sb.append(']').toString();
    }

    /**
     * 把一个对象（及其容器里的元素）的字段打出来 —— 结果对象的契约就靠这个看。
     *
     * <p>为什么要递归：回调 {@code h(proto)} 的参数本身只有一层（{@code proto.b} 是个列表），
     * 真正的文字在列表元素里。所以对集合/数组要往里走一层，再看元素的字段。
     *
     * @param depth  还能往下走几层（3 层够 {@code proto -> list -> element}）
     * @param budget 本行预算，防止一个大对象把日志刷爆
     */
    private static void dumpTree(Object o, int depth, int indent, int[] budget) {
        if (o == null || depth <= 0 || budget[0] <= 0) return;
        final Class<?> c = o.getClass();
        if (c.isArray()) {
            final int n = Math.min(java.lang.reflect.Array.getLength(o), 2);
            for (int i = 0; i < n; i++) {
                dumpTree(java.lang.reflect.Array.get(o, i), depth - 1, indent, budget);
            }
            return;
        }
        if (o instanceof Collection) {
            int i = 0;
            for (Object e : (Collection<?>) o) {
                if (i++ >= 2) break;
                dumpTree(e, depth - 1, indent, budget);
            }
            return;
        }
        if (o instanceof Map) {
            int i = 0;
            for (Object v : ((Map<?, ?>) o).values()) {
                if (i++ >= 2) break;
                dumpTree(v, depth - 1, indent, budget);
            }
            return;
        }
        if (isBoring(c)) return;
        try {
            final Field[] fs = c.getDeclaredFields();
            if (fs.length == 0 || fs.length > 32) return;
            final StringBuilder sb = new StringBuilder(192);
            sb.append("    ");
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append("· ").append(c.getName()).append(" {");
            int n = 0;
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                if (n++ > 0) sb.append(", ");
                try {
                    f.setAccessible(true);
                    sb.append(f.getName()).append('=').append(render(f.get(o)));
                } catch (Throwable t) {
                    sb.append(f.getName()).append("=<").append(t.getClass().getSimpleName())
                      .append('>');
                }
                if (sb.length() > 900) { sb.append(", …"); break; }
            }
            Log.i(TAG, sb.append('}').toString());
            budget[0]--;

            // 再往字段里走：集合/数组/非"无聊"具体类
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                final Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(o);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) continue;
                dumpTree(v, depth - 1, indent + 1, budget);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一次性"能不能自己造"试造 —— 只 newInstance + 读字段，<b>绝不上屏</b>。
     *
     * <p>将来要把国内 ASR 的文字塞进 Gboard，就得自己造结果对象；这里只要确认
     * ①有没有公有空构造 ②造出来的空对象里那个列表字段是不是已经初始化。
     * 只对"实例字段 ≤ 6 个"的数据类做，且每个类只做一次。
     */
    private static void probeConstruct(Object sample) {
        if (sample == null) return;
        final Class<?> c = sample.getClass();
        if (isBoring(c)) return;
        int inst = 0;
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) inst++;
        }
        if (inst == 0 || inst > 6) return;
        if (!sConstructProbed.add(c.getName())) return;
        try {
            final Object fresh = c.getDeclaredConstructor().newInstance();
            final StringBuilder sb = new StringBuilder("  build ").append(c.getName()).append("() ok:");
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                f.setAccessible(true);
                final Object v = f.get(fresh);
                sb.append(' ').append(f.getName()).append('=')
                  .append(v == null ? "null" : v.getClass().getName());
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.i(TAG, "  build " + c.getName() + "() failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private static String stack() {
        final StackTraceElement[] st = Thread.currentThread().getStackTrace();
        final StringBuilder sb = new StringBuilder("stack:");
        int n = 0;
        for (StackTraceElement e : st) {
            final String cn = e.getClassName();
            if (cn.startsWith("java.lang.Thread") || cn.startsWith("dalvik.")
                    || cn.startsWith("android.os.") || cn.startsWith("moe.lovefirefly.")
                    || cn.contains("VoiceProbe")) {
                continue;
            }
            sb.append("\n      ").append(cn).append('.').append(e.getMethodName())
              .append(':').append(e.getLineNumber());
            if (++n >= 6) break;
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "null";
        final String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String descriptor(Method m) {
        final StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(desc(p));
        return sb.append(')').append(desc(m.getReturnType())).toString();
    }

    private static String desc(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private interface Sup<T> {
        T get() throws Throwable;
    }

    private static String safe(Sup<?> s) {
        try {
            return String.valueOf(s.get());
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }
}
