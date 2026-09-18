package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 严格模式第二层：拦住"地球键 → 注入 KEYCODE_LANGUAGE_SWITCH → 系统替它切 subtype"这条路。
 *
 * <p>实测证据：Gboard 改 subtype 时，它自己进程里**没有任何** IMM / IMMS-代理调用
 * （挂满 74 个 hook 也只看到只读调用）→ 切换是<b>系统</b>做的，源头只能是 Gboard
 * 往应用侧注入的那颗 {@code KEYCODE_LANGUAGE_SWITCH}(204)。
 *
 * <p>所以这里挂两层注入点：
 * <ol>
 *   <li>{@code InputMethodService.sendDownUpKeyEvents(int)} / {@code sendKeyChar(char)}（IME 侧的便捷方法）</li>
 *   <li>输入连接自己的 {@code sendKeyEvent(KeyEvent)}（IME 直接往应用发键）</li>
 * </ol>
 */
final class KeyGuard {

    private static final String TAG = "GboardExt";
    private static final int KEYCODE_LANGUAGE_SWITCH = 204;

    /** 诊断：打印每一次 sendKeyEvent（软键盘打字时会非常多，默认关）。 */
    static final boolean DEV_TRACE_KEYS = false;

    private static volatile boolean sServiceHooked;

    /**
     * 连接侧是否已经装过。
     *
     * <p>hook 是**方法级**的：同一颗 {@code sendKeyEvent} 装一次就够，所有实例都会被拦到。
     * 早先没有这个旗标，于是每次会话都重装一遍，撞到 libxposed 的"同一方法最多 64 次"
     * 上限后开始刷 {@code IllegalStateException: Reject hook registration ... already
     * registered 64 times}（一次会话刷几百行，把日志淹了）。
     */
    private static volatile boolean sConnHooked;
    private static volatile int sBlocked;

    private KeyGuard() {}

    /** IME 侧的注入便捷方法。 */
    static void installService(XposedModule module, ClassLoader cl) {
        if (sServiceHooked) return;
        sServiceHooked = true;
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            hook(module, svc, "sendDownUpKeyEvents", int.class);
            hook(module, svc, "sendKeyChar", char.class);
        } catch (Throwable tr) {
            Log.w(TAG, "key guard (service) failed: " + tr);
        }
        Log.i(TAG, "key guard: service side installed");
    }

    /** 输入连接上的发键（方法级 hook，装过一次就跳过）。 */
    static void installConnection(XposedModule module, Object ic) {
        if (ic == null || sConnHooked) return;
        sConnHooked = true;
        try {
            for (Class<?> c = ic.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"sendKeyEvent".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    m.setAccessible(true);
                    final String label = c.getSimpleName() + "#sendKeyEvent";
                    module.hook(m).intercept(chain -> {
                        final Object a = chain.getArg(0);
                        if (a instanceof KeyEvent) {
                            final KeyEvent ev = (KeyEvent) a;
                            if (DEV_TRACE_KEYS) {
                                Log.i(TAG, "ickey " + label + " code=" + ev.getKeyCode()
                                        + " action=" + ev.getAction());
                            }
                            if (SwitchGuard.strictEnabled() && ev.getKeyCode() == KEYCODE_LANGUAGE_SWITCH) {
                                sBlocked++;
                                Log.i(TAG, "strict: blocked injected LANGUAGE_SWITCH (total "
                                        + sBlocked + ")");
                                return Boolean.FALSE;
                            }
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "key guard: hooked " + label);
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "key guard (connection) failed: " + tr);
        }
    }

    private static void hook(XposedModule module, Class<?> cls, String name, Class<?> param) {
        try {
            final Method m = cls.getDeclaredMethod(name, param);
            m.setAccessible(true);
            final Class<?> ret = m.getReturnType();
            module.hook(m).intercept(chain -> {
                final Object a = chain.getArg(0);
                if (DEV_TRACE_KEYS) Log.i(TAG, "svckey " + name + " arg=" + a);
                final boolean isSwitch = (a instanceof Integer && (Integer) a == KEYCODE_LANGUAGE_SWITCH)
                        || (a instanceof Character && (Character) a == (char) 0);
                if (SwitchGuard.strictEnabled() && isSwitch) {
                    sBlocked++;
                    Log.i(TAG, "strict: blocked " + name + "(" + a + ") (total " + sBlocked + ")");
                    return ret == boolean.class ? Boolean.FALSE : null;
                }
                return chain.proceed();
            });
        } catch (Throwable tr) {
            Log.w(TAG, "hook " + name + " failed: " + tr);
        }
    }
}
