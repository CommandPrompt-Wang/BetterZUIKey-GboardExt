package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.MatchType;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 把"文字"翻译成 Gboard 认识的结果对象（{@code aaeo}/{@code aaes}）并喂给回调接口 {@code kls}。
 *
 * <p><b>为什么是反射</b>：这两类是混淆的、每版都变；能拿到的稳定信息只有
 * 「{@code kls#h} 的参数类型 = {@code aaeo}」这一条签名（实测 {@code R aaeo <= kls#h}）。
 * 列表元素 {@code aaes} 连签名都没有（泛型被擦除）⇒ 只能按<b>形状</b>找（见 {@link #discoverItem}）。
 *
 * <p><b>字段映射是版本相关的</b>：18.3.1 实测（run3-vpn-contract.txt）
 * {@code c=文字 / d=是否最终 / b=类型位(11部分,31最终) / f=语言标签 / g=置信度}。
 * 所以这里做成"名字表 + 存在性校验"：一个字段对不上就整体不启用（回退透传），
 * 然后由设置页的「测试链路」按钮暴露出来（见 local/VOICE-ENGINE-INTERFACE.md §2.4）。
 */
final class GboardSink {

    private static final String TAG = "GboardExt";

    // ---- 18.3.1 实测字段名（换版本改这里；对不上就整体降级） ----
    private static final String F_TEXT = "c";
    private static final String F_FINAL = "d";
    private static final String F_KIND = "b";
    private static final String F_LANG = "f";
    private static final String F_CONF = "g";
    private static final String F_ITEMS = "b";     // aaeo 的列表字段

    private static final int KIND_PARTIAL = 11;    // 实测：部分结果
    private static final int KIND_FINAL = 31;      // 实测：最终结果

    private static volatile boolean sReady;
    private static Class<?> sAaeo;
    private static Class<?> sItem;
    private static Field fItems, fText, fFinal, fKind, fLang, fConf;
    private static Method mAdd;
    private static Method mh, md, mf, mg, ma, mc;
    private static volatile String sLang = "cmn-hans-cn";
    private static volatile String sWhy = "未初始化";

    private GboardSink() {
    }

    static boolean ready() {
        return sReady;
    }

    static String why() {
        return sWhy;
    }

    static void setLanguage(String tag) {
        if (tag == null || tag.isEmpty()) return;
        // zh-CN → cmn-hans-cn（Gboard 那侧实测就是这个标签）；其它语言先原样
        if (tag.toLowerCase().startsWith("zh")) sLang = "cmn-hans-cn";
        else sLang = tag;
    }

    /**
     * 一次性解析：结果类 + 字段 + 回调方法。
     *
     * @param bridge 打开的 DexKit（用来按形状找列表元素类）
     * @param aaeo   {@code kls} 单参方法的参数类型
     * @param kls    回调接口
     */
    static synchronized void init(DexKitBridge bridge, Class<?> aaeo, Class<?> kls,
            String superName) {
        try {
            sAaeo = aaeo;
            fItems = findField(aaeo, F_ITEMS, null);
            if (fItems == null) {
                fail("aaeo 上没有列表字段 " + F_ITEMS);
                return;
            }

            sItem = discoverItem(bridge, superName, aaeo, fItems.getType());
            if (sItem == null) {
                fail("按形状找不到结果元素类（父类 " + superName + "）");
                return;
            }

            fText = findField(sItem, F_TEXT, String.class);
            fFinal = findField(sItem, F_FINAL, boolean.class);
            fKind = findField(sItem, F_KIND, int.class);
            fLang = findField(sItem, F_LANG, String.class);
            fConf = findField(sItem, F_CONF, double.class);
            if (fText == null || fFinal == null || fKind == null || fLang == null || fConf == null) {
                fail("结果元素类字段对不上（" + sItem.getName() + "）");
                return;
            }
            mAdd = List.class.getMethod("add", Object.class);

            // 回调方法按**形状**找（名字是混淆的）：h=单参且参数是 aaeo；d=单参 int
            for (Method m : kls.getDeclaredMethods()) {
                final Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == aaeo) mh = m;
                if (ps.length == 1 && ps[0] == int.class) md = m;
            }
            // f()/g() 是 ()V 且无法与 a/c/e 区分 ⇒ 按 18.3.1 的名字找，找不到就跳过（非必需）
            mf = findNoArg(kls, "f");
            mg = findNoArg(kls, "g");
            ma = findNoArg(kls, "a");
            mc = findNoArg(kls, "c");

            if (mh == null) {
                fail("找不到结果回调方法（单参 " + aaeo.getName() + "）");
                return;
            }
            sReady = true;
            Log.i(TAG, "sink ready: aaeo=" + aaeo.getName() + " item=" + sItem.getName()
                    + " h=" + mh.getName() + " d=" + (md != null ? md.getName() : "-")
                    + " f=" + (mf != null ? "y" : "n") + " g=" + (mg != null ? "y" : "n"));
        } catch (Throwable tr) {
            fail("init 异常: " + tr);
        }
    }

    static void fail(String why) {
        sWhy = why;
        sReady = false;
        Log.w(TAG, "sink NOT ready: " + why + " —— 本会话回退透传");
    }

    // ------------------------------------------------------------------ 对外

    /** 部分结果 → {@code kls.h(aaeo{c=text,d=false,b=11})} → setComposingText。 */
    static void partial(Object cb, String text) {
        emit(cb, text, false, 0.0);
    }

    /** 最终结果 → {@code d=true,b=31} → commitText。 */
    static void finalText(Object cb, String text, double conf) {
        emit(cb, text, true, conf);
        finished(cb);
    }

    /**
     * **绕过结果通道**，直接用当前输入连接提交文字。
     *
     * <p>给离线引擎用：它的结果比会话晚几秒（模型加载 + 整段解码），Gboard 那时已经不认结果了
     * （实测 {@code sink emit: final=true} 发了、输入框里没字）。返回 false = 没有可用的输入连接，
     * 调用方应回退到常规结果通道。
     */
    /** 拿一个可用的输入连接（缓存优先，兜底问 IME 服务要）。 */
    private static android.view.inputmethod.InputConnection ic() {
        Object ic = SymbolNormHook.currentIc();
        if (!(ic instanceof android.view.inputmethod.InputConnection)) {
            // 兜底：直接问 IME 服务要当前输入连接（缓存可能是空的：这一轮没敲过键）
            ic = ServiceProbe.currentInputConnection();
        }
        return ic instanceof android.view.inputmethod.InputConnection
                ? (android.view.inputmethod.InputConnection) ic : null;
    }

    /** 离线 partial：把"到目前为止的全文"作为组合中文本写进去（与最终的 IC 提交同一通道）。 */
    static boolean composingDirect(String text) {
        final android.view.inputmethod.InputConnection ic = ic();
        if (ic == null) return false;
        try {
            ic.setComposingText(text, 1);
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "sink: 组合中文本直接写入失败: " + tr);
            return false;
        }
    }

    static boolean commitDirect(String text) {
        final android.view.inputmethod.InputConnection ic = ic();
        if (ic == null) {
            Log.w(TAG, "sink: 没有可用的输入连接，回退结果通道");
            return false;
        }
        try {
            ((android.view.inputmethod.InputConnection) ic).commitText(text, 1);
            Log.i(TAG, "sink: 直接提交（IC）\"" + text + "\"");
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "sink: 直接提交失败: " + tr);
            return false;
        }
    }

    static void level(Object cb, int v) {
        if (!sReady || cb == null || md == null) return;
        try {
            md.invoke(cb, v);
        } catch (Throwable ignored) {
        }
    }

    static void started(Object cb) {
        invoke0(mf, cb);
    }

    /**
     * 会话开始时的回调序（照实测的 S3 契约来）：
     * <pre>
     *   f()            ← 识别开始（麦克风开）
     *   a(); c()       ← 实测里在第一条结果之前成对出现两次（见 run3-vpn-contract.txt）
     * </pre>
     * 第一版 P0 只发了 {@code f()}，结果 Gboard 收到了 {@code h()} 却<b>没有任何上屏动作</b>
     * —— 怀疑 {@code a()/c()} 是把内部状态推进到"可以接受结果"的那一步，所以补上。
     */
    static void onStart(Object cb) {
        invoke0(mf, cb);
        pair(cb);
    }

    static void pair(Object cb) {
        invoke0(ma, cb);
        invoke0(mc, cb);
    }

    static void finished(Object cb) {
        invoke0(mg, cb);
    }

    private static void invoke0(Method m, Object cb) {
        if (m == null || cb == null) return;
        try {
            m.invoke(cb);
        } catch (Throwable ignored) {
        }
    }

    private static void emit(Object cb, String text, boolean isFinal, double conf) {
        if (!sReady || cb == null || text == null) return;
        Object proto = null;
        try {
            proto = sAaeo.getDeclaredConstructor().newInstance();
        } catch (Throwable tr) {
            Log.w(TAG, "sink: aaeo 构造失败: " + tr);
            return;
        }
        final List<Object> items;
        try {
            items = mutableList(proto);
        } catch (Throwable tr) {
            Log.w(TAG, "sink: 取可变列表失败: " + tr);
            return;
        }
        if (items == null) {
            Log.w(TAG, "sink: 列表不可用");
            return;
        }
        try {
            final Object item = newItem();
            fText.set(item, text);
            fFinal.setBoolean(item, isFinal);
            fKind.setInt(item, isFinal ? KIND_FINAL : KIND_PARTIAL);
            fLang.set(item, sLang);
            fConf.setDouble(item, conf);
            items.add(item);
        } catch (Throwable tr) {
            Log.w(TAG, "sink: 填结果对象失败: " + tr);
            return;
        }
        try {
            mh.invoke(cb, proto);
            if (VoiceEngineHost.DEV_TRACE) {
                Log.i(TAG, "sink emit: final=" + isFinal + " text=\"" + text + "\"");
            }
        } catch (Throwable tr) {
            Log.w(TAG, "sink: 调结果回调失败: " + tr);
        }
    }

    /**
     * 拿一个<b>可变</b>的列表。
     *
     * <p><b>踩过的坑</b>：protobuf 风格的"空 repeated 字段"是<b>不可变单例</b> ——
     * 直接对它 {@code add} 会抛 {@code UnsupportedOperationException}（实测，第一版 P0 就死在这）。
     * 好消息是同一个类（实测 {@code zzt}）的<b>无参构造</b>造出来的是可变的（它内部给
     * "是否可变"传了 true），所以"拿同类新实例替换掉那个字段"即可，不用知道类名。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> mutableList(Object proto) throws Exception {
        Object cur = fItems.get(proto);
        Object fresh = null;
        final Class<?> lc = cur != null ? cur.getClass() : fItems.getType();
        try {
            final java.lang.reflect.Constructor<?> c = lc.getDeclaredConstructor();
            c.setAccessible(true);
            fresh = c.newInstance();
        } catch (Throwable tr) {
            Log.i(TAG, "sink: 造可变列表失败（退用现有实例）: " + tr);
        }
        if (fresh == null) fresh = cur;
        if (fresh != null && fresh != cur) {
            try {
                fItems.set(proto, fresh);
            } catch (Throwable tr) {
                Log.w(TAG, "sink: 回填列表字段失败: " + tr);
            }
        }
        return (List<Object>) fresh;
    }

    /** 新建一个元素实例：构造是 private（实测），先正常反射、失败再 Unsafe。 */
    private static Object newItem() throws Exception {
        try {
            final java.lang.reflect.Constructor<?> c = sItem.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable tr) {
            return unsafeAllocate(sItem);
        }
    }

    private static Object unsafeAllocate(Class<?> c) throws Exception {
        final Class<?> uc = Class.forName("sun.misc.Unsafe");
        final Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        final Object unsafe = f.get(null);
        return uc.getMethod("allocateInstance", Class.class).invoke(unsafe, c);
    }

    // ------------------------------------------------------------------ 发现

    /**
     * 按形状找列表元素类。三个条件一起用（都不依赖混淆名）：
     * <ol>
     *   <li>与 {@code aaeo} <b>同父类</b>（实测 {@code zyf}）；</li>
     *   <li>含 {@code aaeo} 那个列表字段的<b>同一个 repeated 基类型</b>（实测 {@code zyv}）——
     *       这是"它也是同一种消息"的最强信号；</li>
     *   <li>同时具备 {String, boolean, int, double} 四种字段（字段**类型**不会被混淆）。</li>
     * </ol>
     * 再加一条保险：字段数 ≤ 64（实测另一个候选 {@code ydh} 有 184 个字段，是别的东西）。
     */
    private static Class<?> discoverItem(DexKitBridge bridge, String superName, Class<?> aaeo,
            Class<?> listBase) {
        try {
            final FieldsMatcher fields = FieldsMatcher.create()
                    .matchType(MatchType.Contains)
                    .add(FieldMatcher.create().type(String.class))
                    .add(FieldMatcher.create().type(boolean.class))
                    .add(FieldMatcher.create().type(int.class))
                    .add(FieldMatcher.create().type(double.class));
            if (listBase != null) {
                fields.add(FieldMatcher.create().type(listBase));
            }
            final ClassDataList cs = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create()
                            .superClass(superName, StringMatchType.Equals, false)
                            .fields(fields)));
            Log.i(TAG, "sink discover: 候选 " + cs.size() + " 个 (listBase="
                    + (listBase != null ? listBase.getName() : "-") + ")");
            Class<?> hit = null;
            for (ClassData cd : cs) {
                if (cd.getName().equals(aaeo.getName())) continue;
                if (cd.getFieldCount() > 64) {
                    Log.i(TAG, "    skip " + cd.getName() + " fields=" + cd.getFieldCount());
                    continue;
                }
                Log.i(TAG, "    cand " + cd.getName() + " fields=" + cd.getFieldCount());
                if (hit != null) {
                    Log.w(TAG, "    ! 多个候选，放弃（宁可透传）");
                    return null;
                }
                hit = cd.getInstance(aaeo.getClassLoader());
            }
            return hit;
        } catch (Throwable tr) {
            Log.w(TAG, "sink discover 失败: " + tr);
            return null;
        }
    }

    private static Field findField(Class<?> c, String name, Class<?> type) {
        try {
            final Field f = c.getField(name);
            if (type != null && f.getType() != type) return null;
            return f;
        } catch (Throwable tr) {
            return null;
        }
    }

    private static Method findNoArg(Class<?> c, String name) {
        try {
            final Method m = c.getDeclaredMethod(name);
            return m.getReturnType() == void.class && m.getParameterCount() == 0 ? m : null;
        } catch (Throwable tr) {
            return null;
        }
    }
}
