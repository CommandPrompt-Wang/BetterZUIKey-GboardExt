package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 严格模式：把"切语言"收归框架。
 *
 * <p>Gboard 自己切语言（地球键 / 语言列表）的路径实测不固定，所以分三层挂：
 * <ol>
 *   <li>客户端 {@code InputMethodManager} 的 set/switch 入口；</li>
 *   <li>输入法自己那侧 {@code InputMethodService.switchToNextInputMethod} /
 *       {@code switchToPreviousInputMethod}；</li>
 *   <li>IMMS 的 Binder 代理 {@code IInputMethodManager$Stub$Proxy} —— 它绕开客户端直连服务端。
 *       代理实例活在<b>调用方进程</b>里，所以挂它等于"只拦 Gboard 自己发起的调用"。</li>
 * </ol>
 *
 * <p>拦截规则：只拦"当前输入法内部的 subtype 切换"。{@code setInputMethod*} 的目标是
 * <b>别的</b>输入法时放行（那是换输入法，不是换语言）。BZK 的快捷键走 system_server 的 IMMS，
 * 不经过本进程，因此不受影响。
 *
 * <p>装不上、认不出的一律放行并打日志：宁可漏拦，不乱拦。
 */
final class SwitchGuard {

    private static final String TAG = "GboardExt";

    /** 临时诊断开关：切换调用打调用栈（查 "Shift+Space 为何切语言" 用）。 */
    private static final boolean DEV_TRACE_STACK = false;

    /**
     * 严格模式开关：由 App 侧配置驱动（广播 / 2 秒轮询都会同步）。
     *
     * <p><b>初值必须与设置页的默认值一致（关）</b> —— 模块在收到第一条广播之前就用它；
     * 若这里取 {@code true}，会出现"界面显示关、实际却是开的"（踩过）。
     */
    private static volatile boolean sStrict = false;

    static void setStrict(boolean on) {
        if (sStrict != on) Log.i(TAG, "strict switch -> " + on);
        sStrict = on;
    }

    static boolean strictEnabled() {
        return sStrict;
    }

    /** 诊断期：把走代理层的每次调用都打出来（用来找出地球键到底走哪条路）。 */
    static final boolean DEV_TRACE_AIDL = false;

    private static final String[] IMM_NAMES = {
            "switchToNextInputMethod",
            "switchToPreviousInputMethod",
            "setInputMethodSubtype",
            "setCurrentInputMethodSubtype",
            "setInputMethodAndSubtype",
            "setInputMethod",
    };

    private static volatile boolean sInstalled;
    private static volatile int sBlocked;

    private SwitchGuard() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        int hooked = 0;
        hooked += hookImm(module, cl);
        hooked += hookService(module, cl);
        hooked += hookAidlProxy(module, cl);
        hooked += hookPrivilegedOps(module, cl);
        sInstalled = true;
        Log.i(TAG, "strict switch guard installed: " + hooked + " hook(s), STRICT=" + sStrict);
    }

    // ---- ① 客户端 InputMethodManager ----

    private static int hookImm(XposedModule module, ClassLoader cl) {
        int n = 0;
        try {
            final Class<?> imm = Class.forName("android.view.inputmethod.InputMethodManager", false, cl);
            for (Method m : imm.getDeclaredMethods()) {
                if (!isAny(m.getName(), IMM_NAMES)) continue;
                Log.i(TAG, "imm candidate " + m.getName() + params(m));
                m.setAccessible(true);
                n += hookSwitch(module, m, "imm." + m.getName());
            }
        } catch (Throwable tr) {
            Log.w(TAG, "imm hooks failed: " + tr);
        }
        return n;
    }

    // ---- ② 输入法自己那侧 ----

    private static int hookService(XposedModule module, ClassLoader cl) {
        int n = 0;
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                final String name = m.getName();
                if (!"switchToNextInputMethod".equals(name)
                        && !"switchToPreviousInputMethod".equals(name)
                        && !"switchInputMethod".equals(name)) continue;
                Log.i(TAG, "svc switch candidate " + name + params(m));
                m.setAccessible(true);
                n += hookSwitch(module, m, "svc." + name);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "svc switch hooks failed: " + tr);
        }
        return n;
    }

    // ---- ③ IMMS 的 Binder 代理 ----

    private static int hookAidlProxy(XposedModule module, ClassLoader cl) {
        int n = 0;
        for (String cn : new String[]{
                "com.android.internal.view.IInputMethodManager$Stub$Proxy",
                "com.android.internal.view.IInputMethodManager$Stub"}) {
            try {
                final Class<?> c = Class.forName(cn, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    // 代理层方法很多，全挂上；只有"像切 subtype/输入法"的那几个才拦，其余只记日志
                    if (m.getName().startsWith("switch") || m.getName().contains("Subtype")
                            || m.getName().startsWith("setInputMethod")) {
                        Log.i(TAG, "aidl candidate " + c.getSimpleName() + "." + m.getName()
                                + params(m) + (isLanguageSwitch(m.getName()) ? " [BLOCK]" : ""));
                    }
                        m.setAccessible(true);
                    n += hookSwitch(module, m, "aidl." + m.getName());
                }
            } catch (Throwable tr) {
                Log.i(TAG, "aidl " + cn + " not available: " + tr);
            }
        }
        return n;
    }

    /** 统一的"要拦就拦、要放就放"钩子：名字像切 subtype 的按规则判断，其余一律放行。 */
    private static int hookSwitch(XposedModule module, Method m, String label) {
        final String name = m.getName();
        final boolean isSwitch = isLanguageSwitch(name);
        final Class<?> ret = m.getReturnType();
        module.hook(m).intercept(chain -> {
            // 注：曾试过在这里吞掉"被吃组合键的 Shift"引发的切换 —— 实测拦截命中但语言照样变，
            // 因为 Gboard 是先在内部改完语言才发这个调用（拦调用太晚）。最终解法在 KeyRouter.hotkey：
            // 被吃组合键的**抬起放行**，Gboard 判定阶段就不会进（switchcall 一次都不出现）。
            // Gboard 的 Shift 单击中/英：用户要求"不必干预" ⇒ 不拦、也不接管（仅中英那套）
            if (KeyRouter.shiftJustPressed()) {
                Log.i(TAG, "switchcall " + label + " -> pass (shift 中/英 toggle) @"
                        + android.os.SystemClock.uptimeMillis());
                if (DEV_TRACE_STACK) {
                    // 临时诊断：谁发起的这次切换（看 Gboard 是从哪条路径来的）
                    for (StackTraceElement e : new Throwable().getStackTrace()) {
                        Log.i(TAG, "    at " + e);
                    }
                }
                return chain.proceed();
            }
            if (DEV_TRACE_AIDL && label.startsWith("aidl.")) {
                Log.i(TAG, "aidl call " + label);
            }
            if (!isSwitch) return chain.proceed();
            // 带"目标 id"的形态：只有目标是 Gboard 自己时才拦（换别的输入法放行）
            final boolean hasTargetId = "setInputMethodAndSubtype".equals(name)
                    || "setInputMethod".equals(name)
                    || "switchInputMethod".equals(name);
            boolean block = !hasTargetId;
            if (!block) {
                // IMM/IMMS 的签名是 (IBinder token, String id, ...)：按"第一个 String 参数"取 id
                // （踩过：当成 getArg(0) 会让这些方法全部漏拦）
                String id = null;
                for (Object a : chain.getArgs()) {
                    if (a instanceof String) { id = (String) a; break; }
                }
                block = id != null && id.startsWith(BridgeHook.TARGET_PKG + "/");
            }
            // switchToNextInputMethod(onlyCurrentIme) 的语义：
            //   true  = 在本输入法内换语言（严格模式要拦的就是这个）
            //   false = 换到另一个输入法（必须放行 —— 否则连 Ctrl+Space 这类都会被拦掉，实测踩过）
            if (block && name.startsWith("switchTo")) {
                for (Object a : chain.getArgs()) {
                    if (a instanceof Boolean && !((Boolean) a)) return chain.proceed();
                }
            }
            if (!sStrict) return chain.proceed();      // 开关关掉 → 一律放行（hook 留着，实时生效）
            // 诊断：switch 类入口的每次调用都记（含参数与结论），用来回答"到底是谁拦的"
            if (!block) {
                Log.i(TAG, "switchcall " + label + " args=" + chain.getArgs() + " -> pass");
                return chain.proceed();
            }
            sBlocked++;
            Log.i(TAG, "switchcall " + label + " args=" + chain.getArgs()
                    + " -> BLOCK (total " + sBlocked + ")");
            return noOp(ret);
        });
        return 1;
    }

    /** IME 找系统执行切换用的特权 binder（实测地球键时 code=6/9 有流量）。 */
    private static int hookPrivilegedOps(XposedModule module, ClassLoader cl) {
        int n = 0;
        for (String cn : new String[]{
                "com.android.internal.inputmethod.IInputMethodPrivilegedOperations$Stub$Proxy",
                "com.android.internal.inputmethod.IInputMethodPrivilegedOperations$Stub"}) {
            try {
                final Class<?> c = Class.forName(cn, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    final String name = m.getName();
                    if (!name.contains("Subtype") && !name.startsWith("switch")
                            && !name.startsWith("setInputMethod")) continue;
                    Log.i(TAG, "priv candidate " + c.getSimpleName() + "." + name + params(m));
                        m.setAccessible(true);
                    n += hookSwitch(module, m, "priv." + name);
                }
            } catch (Throwable tr) {
                Log.i(TAG, "priv " + cn + " not available: " + tr);
            }
        }
        return n;
    }

    /**
     * 真正"切换当前语言/键盘布局"的入口 —— 只有这些才拦。
     *
     * <p>不用"名字里含 Subtype / 以 switch 开头"这种模糊规则（踩过：把
     * {@code getCurrentInputMethodSubtype} 这种读方法、以及
     * {@code setAdditionalInputMethodSubtypes} / {@code setExplicitlyEnabledInputMethodSubtypes}
     * 这类"声明有哪些语言"的集合管理也一起拦了，后者会破坏输入法的语言管理）。
     */
    private static boolean isLanguageSwitch(String name) {
        return "switchKeyboardLayoutAsync".equals(name)      // Ctrl+Space 走的这条
                || "switchToNextInputMethod".equals(name)
                || "switchToPreviousInputMethod".equals(name)
                || "setInputMethodAndSubtype".equals(name)
                || "setInputMethod".equals(name)
                || "setCurrentInputMethodSubtype".equals(name)
                || "switchInputMethod".equals(name);
    }

    private static boolean isAny(String name, String[] names) {
        for (String t : names) {
            if (t.equals(name)) return true;
        }
        return false;
    }

    private static Object noOp(Class<?> ret) {
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        return null;
    }

    private static String params(Method m) {
        final StringBuilder sb = new StringBuilder("(");
        for (Class<?> c : m.getParameterTypes()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(c.getSimpleName());
        }
        return sb.append(')').toString();
    }
}
