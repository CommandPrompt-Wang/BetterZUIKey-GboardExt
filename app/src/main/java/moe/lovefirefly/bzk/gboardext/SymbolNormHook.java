package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 符号归一的挂点：IME 进程里的 {@code android.inputmethodservice.RemoteInputConnection}。
 *
 * <p><b>为什么挂它</b>：这是"标点真正上屏"的必经之路，而且它是<b>框架类</b>
 * （不被混淆、换 Gboard 版本也不会变）—— 于是整个功能不需要碰 Gboard 的任何内部类，
 * 也就没有硬编码混淆名的风险。
 *
 * <p>映射表硬编码在 {@link SymbolNorm}（不配置、不广播）。
 *
 * <p><b>只在中文态生效</b>：判据是当前 subtype 的 locale（{@link ServiceProbe#isChinese()}）。
 * 日语有完善的候选/组合框，一律不碰；英文态本来就是半角，改了也是空转。
 *
 * <p>组合态（{@code setComposingText}）也挂上：中文态下符号通常直接上屏，但拼音字母
 * 的组合态也会经过这里 —— 表里没有 ASCII 字母，所以那些字符天然不会被改。
 *
 * <p><b>必须挂"全部重载"</b>：Android 13+（API 33）给 {@code commitText} /
 * {@code setComposingText} 加了带 {@code TextAttribute} 的三参数版本，Gboard 在
 * 新系统上就走那条 —— 只挂两参数版本会漏掉大部分符号（实测：中文态只有第一个
 * {@code ｛} 被改，其余原样过去）。
 */
final class SymbolNormHook {

    private static final String TAG = "GboardExt";

    /** 开发期：打印每次被改写的提交对（验证期开过，已收敛）。 */
    static final boolean DEV_TRACE = false;

    private static volatile boolean sInstalled;

    /** 智能编号（中文特供）：数字后面紧跟的 。/） 换成半角（1. 2) 这种）。 */
    private static volatile boolean sSmartNumber = true;

    /** 功能开关「智能中文标点」：关掉就跳过语义层（只留宽度层）。 */
    private static volatile boolean sSmartPunct = true;

    /** 功能开关「全角模式」：关掉 ⇒ 状态位被忽略，恒半角。 */
    private static volatile boolean sFullWidthFeature = true;

    /** 功能开关「中英文标点」：关掉 ⇒ 状态位被忽略，恒中文标点。 */
    private static volatile boolean sEnPunctFeature = true;

    /** 最近一次真正上屏的最后一个字符 —— 智能编号靠它判断"前面是不是数字"。 */
    private static volatile char sLast;

    /**
     * 问编辑器"光标前一个字符"。
     *
     * <p>为什么需要它：中文态的**数字键不走 InputConnection**（实测：连打 `5）`，钩子里只看到
     * `）`，`sLast` 还是空/`（` ✗）—— 数字大概是走 KeyEvent 直接被 App 插进去的。
     * 所以"前一个是不是数字"只能向编辑器问。取不到时退回 {@link #sLast}。
     */
    private static char beforeCursor(Object ic) {
        try {
            if (ic instanceof android.view.inputmethod.InputConnection) {
                final CharSequence cs = ((android.view.inputmethod.InputConnection) ic)
                        .getTextBeforeCursor(1, 0);
                if (cs != null && cs.length() > 0) return cs.charAt(cs.length() - 1);
            }
        } catch (Throwable ignored) {
        }
        return sLast;
    }

    static void setSmartPunct(boolean on) {
        if (sSmartPunct != on) Log.i(TAG, "smartPunct -> " + on);
        sSmartPunct = on;
    }

    static void setFullWidthFeature(boolean on) {
        if (sFullWidthFeature != on) Log.i(TAG, "fullwidth feature -> " + on);
        sFullWidthFeature = on;
    }

    static void setEnPunctFeature(boolean on) {
        if (sEnPunctFeature != on) Log.i(TAG, "enPunct feature -> " + on);
        sEnPunctFeature = on;
    }

    /** 物理补全的功能开关（由 BroadcastConfig 推过来；横幅文案要判断它）。 */
    private static volatile boolean sPhysCompleteFeature;

    static void setPhysCompleteFeature(boolean on) {
        if (sPhysCompleteFeature != on) Log.i(TAG, "physComplete feature -> " + on);
        sPhysCompleteFeature = on;
    }

    static boolean physCompleteFeature() {
        return sPhysCompleteFeature;
    }

    static boolean smartPunct() {
        return sSmartPunct;
    }

    static boolean fullWidthFeature() {
        return sFullWidthFeature;
    }

    static boolean enPunctFeature() {
        return sEnPunctFeature;
    }

    static void setSmartNumber(boolean on) {
        if (sSmartNumber != on) Log.i(TAG, "smartNumber -> " + on);
        sSmartNumber = on;
    }

    private SymbolNormHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        final Class<?> cls;
        try {
            cls = Class.forName("android.inputmethodservice.RemoteInputConnection", false, cl);
        } catch (Throwable tr) {
            Log.w(TAG, "norm: RemoteInputConnection not found: " + tr);
            return;
        }
        // 按名字枚举全部重载（2 参、3 参 TextAttribute…），只要求第一个参数是 CharSequence
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            final String nm = m.getName();
            if (!"commitText".equals(nm) && !"setComposingText".equals(nm)) continue;
            final Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 2 || !CharSequence.class.isAssignableFrom(ps[0])) continue;
            if (hook(module, m, nm)) n++;
        }
        Log.i(TAG, "norm hooked " + n + " method(s) on " + cls.getSimpleName());
    }

    /**
     * 诊断：把这次提交的调用栈打出来。
     *
     * <p>目的：区分"按了反斜杠键"和"从符号页/候选点了顿号"——两者提交的字符完全一样，
     * 但**发起路径不同**，栈帧序列必然不同（按键走键盘触摸分发；符号页走面板/布局切换；
     * 候选走候选选择）。如果提交被 post 到 Handler 线程，栈里至少能看到是哪个 Runnable
     * （{@code Lmn.run} / {@code Lmza.run}）——那本身就是一条判据。
     */
    private static void logStack(String what) {
        try {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder sb = new StringBuilder("probe stack[").append(what)
                    .append("] thread=").append(Thread.currentThread().getName());
            int n = 0;
            for (StackTraceElement e : st) {
                final String cn = e.getClassName();
                if (cn.startsWith("java.lang.Thread") || cn.startsWith("dalvik.")
                        || cn.startsWith("android.os.Looper") || cn.startsWith("android.os.Handler")
                        || cn.startsWith("android.os.MessageQueue")) {
                    continue;                      // 跳过 VM / looper 噪音
                }
                sb.append("\n      ").append(cn).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber());
                if (++n >= 12) break;
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static boolean hook(XposedModule module, Method m, String name) {
        try {
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a0 = chain.getArg(0);
                if (!(a0 instanceof CharSequence)) return chain.proceed();
                // 我们自己注入的闭字符：原样放行，别被标点管线二次改写
                if (AutoPair.isInjecting()) return chain.proceed();
                final boolean cn = ServiceProbe.isChinese();
                // Enter 探针（plan.md P0.2）：把组词/上屏的每一次都打出来，
                // 用来看"拼音在 Enter 那一刻是怎么上屏的"。只在这个探针开着时刷。
                // 拼音栏有没有字：setComposingText(非空) = 有；commitText = 这一段结束。
                // 中文态 Enter（EnterFix）就靠这个判据，所以**无条件**维护它（两次 volatile 写，很便宜）。
                if (name.equals("setComposingText")) {
                    EnterFix.setComposing(a0.toString().length() > 0);
                } else if (name.equals("commitText")) {
                    EnterFix.setComposing(false);
                }
                if (EnterFix.DEV_TRACE) {
                    Log.i(TAG, "enter IC " + name + "[\"" + a0 + "\"] cn=" + cn
                            + " @" + Thread.currentThread().getName());
                }
                String out = null;
                if (cn) {
                    final String raw = a0.toString();
                    // 智能编号：数字后面的 。/） 用半角。
                    // 判据比搜狗那版更稳：看提交文本的【末字符】，它前面（同一次提交里）或
                    // 上一次上屏的字符是数字就换 —— 这样 "2）" 一起上屏也能命中。
                    String base = raw;
                    if (sSmartNumber && !base.isEmpty()) {
                        final char tail = base.charAt(base.length() - 1);
                        if (tail == '\u3002' || tail == '\uFF09') {
                            final char prev = base.length() >= 2
                                    ? base.charAt(base.length() - 2)
                                    : beforeCursor(chain.getThisObject());
                            if (prev >= '0' && prev <= '9') {
                                base = base.substring(0, base.length() - 1)
                                        + (tail == '\u3002' ? "." : ")");
                            }
                        }
                    }
                    // —— 语义层（该出什么字符）——
                    // 智能中文标点（开关1）与中英文标点（开关3）：英文标点状态位开 ⇒ 按键盘显示的
                    // ASCII 标点输出（中文标点还原 + 下面宽度层拉回半角）。
                    String shaped = base;
                    final boolean en = sEnPunctFeature && GboardState.enPunct();
                    if (en) {
                        final String a = SymbolNorm.toAsciiPunct(shaped);
                        if (a != null) shaped = a;
                    } else if (sSmartPunct) {
                        final String r = SymbolNorm.applySemantic(shaped, SymbolNorm.longMarks());
                        if (r != null) shaped = r;
                    }
                    // —— 宽度层（宽窄）——
                    // 全角模式（开关2）的状态位：开 = 符号转全角；关 = 拉回半角（默认，且是今天的
                    // 行为 ⇒ 零回归）。区间规则，理由见 SymbolNorm 类注释。
                    final String w = sFullWidthFeature && GboardState.fullwidth()
                            ? SymbolNorm.toFullWidth(shaped)
                            : SymbolNorm.toHalfWidth(shaped);
                    if (w != null) shaped = w;
                    if (!shaped.equals(base)) out = shaped;
                    // 记下这次真正上屏的最后一个字符
                    final String shown = out != null ? out : raw;
                    sLast = shown.isEmpty() ? 0 : shown.charAt(shown.length() - 1);
                }
                // 诊断：带全角字符的提交，无论改没改都打一行（只打这种，拼音字母不会刷屏）
                final String s0 = a0.toString();
                final boolean interesting = out != null || SymbolNorm.hasFullWidth(s0)
                        || s0.indexOf('/') >= 0 || s0.indexOf('\\') >= 0
                        || s0.indexOf('\u3001') >= 0;
                if (DEV_TRACE && cn && interesting) {
                    Log.i(TAG, "probe commit " + name + "[" + m.getParameterCount() + "] \""
                            + s0 + "\"" + (out == null ? "  (未命中)" : " -> \"" + out + "\""));
                    logStack(s0);
                }
                final Object result;
                if (out == null) {
                    result = chain.proceed();
                } else {
                    final Object[] args = chain.getArgs().toArray();
                    args[0] = out;
                    result = chain.proceed(args);
                }
                // 开字符上屏后补闭字符（软/物理各由各自开关控制；只在 commitText 这条路上做）
                if (cn && "commitText".equals(name)) {
                    AutoPair.maybeInject(chain.getThisObject(), out != null ? out : (CharSequence) a0);
                }
                return result;
            });
            Log.i(TAG, "norm hooked " + name + "/" + m.getParameterCount());
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "norm hook " + name + " failed: " + tr);
            return false;
        }
    }
}
