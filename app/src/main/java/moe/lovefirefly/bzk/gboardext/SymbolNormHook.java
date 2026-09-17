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
    static final boolean DEV_TRACE = true;

    private static volatile boolean sInstalled;

    /** 智能编号（中文特供）：数字后面紧跟的 。/） 换成半角（1. 2) 这种）。 */
    private static volatile boolean sSmartNumber = true;

    /** 最近一次真正上屏的最后一个字符 —— 智能编号靠它判断"前面是不是数字"。 */
    private static volatile char sLast;

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
                final boolean cn = ServiceProbe.isChinese();
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
                                    ? base.charAt(base.length() - 2) : sLast;
                            if (prev >= '0' && prev <= '9') {
                                base = base.substring(0, base.length() - 1)
                                        + (tail == '\u3002' ? "." : ")");
                            }
                        }
                    }
                    if (DEV_TRACE && (raw.length() <= 2 || raw.indexOf('\uFF09') >= 0
                            || raw.indexOf('\u3002') >= 0 || raw.indexOf(')') >= 0)) {
                        final StringBuilder cp = new StringBuilder("probe num raw=");
                        for (int i = 0; i < raw.length(); i++) {
                            cp.append(Integer.toHexString(raw.charAt(i))).append(' ');
                        }
                        cp.append(" last=0x").append(Integer.toHexString(sLast))
                          .append(" len=").append(raw.length());
                        Log.i(TAG, cp.toString());
                    }
                    final String norm = SymbolNorm.apply(base);
                    if (norm != null) out = norm;
                    else if (!base.equals(raw)) out = base;
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
                if (out == null) return chain.proceed();
                final Object[] args = chain.getArgs().toArray();
                args[0] = out;
                return chain.proceed(args);
            });
            Log.i(TAG, "norm hooked " + name + "/" + m.getParameterCount());
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "norm hook " + name + " failed: " + tr);
            return false;
        }
    }
}
