package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 严格模式：把"切语言"收归框架。
 *
 * <p>Gboard 自己切语言（地球键 / 空格滑动 / 语言列表）走的是 {@code InputMethodManager}
 * 的那几个入口（静态分析：{@code nhi.ay(Z)} → {@code switchToNextInputMethod} 等）。
 * 严格模式把它们拦成 no-op，于是语言只会因为<b>框架侧</b>的变化而变
 * （BZK 的快捷键走 system_server 的 IMMS，不经过这里，不受影响）—— 与 Sogou 那边的严格模式同义。
 *
 * <p>只拦"当前输入法内部的 subtype 切换"：{@code setInputMethodAndSubtype} 若目标是
 * <b>别的</b>输入法，照旧放行（那是换输入法，不是换语言）。
 */
final class SwitchGuard {

    private static final String TAG = "GboardExt";

    /** 严格模式总开关（暂时是编译期常量，UI 开关放到下一轮）。 */
    static final boolean STRICT = true;

    private static final String[] TARGET_NAMES = {
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
        try {
            final Class<?> imm = Class.forName("android.view.inputmethod.InputMethodManager", false, cl);
            int hooked = 0;
            for (Method m : imm.getDeclaredMethods()) {
                if (!isTarget(m.getName())) continue;
                Log.i(TAG, "imm candidate " + m.getName() + params(m));
                if (!STRICT) continue;
                m.setAccessible(true);
                final String name = m.getName();
                final Class<?> ret = m.getReturnType();
                module.hook(m).intercept(chain -> {
                    // 默认拦：切 subtype / 切下一个输入法
                    boolean block = !("setInputMethodAndSubtype".equals(name)
                            || "setInputMethod".equals(name));
                    if (!block) {
                        // 换的是"别的输入法"→ 放行；换的是自己（Gboard）→ 那是换语言，拦。
                        // 注意：IMM 这些方法是 (IBinder token, String id, ...)，id 不在第 0 个参数上，
                        // 所以按"第一个 String 参数"取（踩过：当成 getArg(0) 会导致全部放行）。
                        String id = null;
                        for (Object a : chain.getArgs()) {
                            if (a instanceof String) { id = (String) a; break; }
                        }
                        block = id != null && id.startsWith(BridgeHook.TARGET_PKG + "/");
                    }
                    if (!block) return chain.proceed();
                    sBlocked++;
                    final StringBuilder sb = new StringBuilder("strict: blocked ").append(name);
                    for (Object a : chain.getArgs()) sb.append(' ').append(a);
                    sb.append(" (total ").append(sBlocked).append(')');
                    Log.i(TAG, sb.toString());
                    return noOp(ret);
                });
                hooked++;
            }
            // IME 自己那一侧也有一套（Gboard 作为输入法更可能走这条）：
            // InputMethodService.switchToNextInputMethod(boolean) / switchToPreviousInputMethod()
            try {
                final Class<?> svc = Class.forName(
                        "android.inputmethodservice.InputMethodService", false, cl);
                for (Method m : svc.getDeclaredMethods()) {
                    final String n = m.getName();
                    if (!"switchToNextInputMethod".equals(n)
                            && !"switchToPreviousInputMethod".equals(n)) continue;
                    Log.i(TAG, "svc switch candidate " + n + params(m));
                    if (!STRICT) continue;
                    m.setAccessible(true);
                    final Class<?> ret = m.getReturnType();
                    module.hook(m).intercept(chain -> {
                        sBlocked++;
                        Log.i(TAG, "strict: blocked InputMethodService." + n
                                + " (total " + sBlocked + ")");
                        return noOp(ret);
                    });
                    hooked++;
                }
            } catch (Throwable tr) {
                Log.w(TAG, "svc switch hooks failed: " + tr);
            }
            sInstalled = true;
            Log.i(TAG, "strict switch guard installed: " + hooked + " hook(s), STRICT=" + STRICT);
        } catch (Throwable tr) {
            sInstalled = true;
            Log.w(TAG, "strict guard install failed: " + tr);
        }
    }

    private static boolean isTarget(String name) {
        for (String t : TARGET_NAMES) {
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
