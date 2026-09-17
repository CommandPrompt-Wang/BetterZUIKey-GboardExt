package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 第一轮的只读探针：挂 {@code android.inputmethodservice.InputMethodService} 的框架方法。
 *
 * <p>为什么挂框架方法而不是 Gboard 自己的类：框架类/方法名不被混淆，换版本也不会变 ——
 * 这正是"不硬编码混淆名"的第一步。运行时用 {@code getThisObject().getClass()} 就能拿到
 * Gboard 真正实现的那个类（静态分析里是 {@code nix}，但代码里不出现它）。
 */
final class ServiceProbe {

    private static final String TAG = "GboardExt";
    private static volatile boolean sInstalled;
    private static volatile boolean sWarned;
    private static volatile boolean sWatchStarted;

    private ServiceProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        final Class<?> svc;
        try {
            svc = Class.forName("android.inputmethodservice.InputMethodService", false, cl);
        } catch (Throwable tr) {
            warnOnce("InputMethodService not found: " + tr);
            sInstalled = true;
            return;
        }
        hook(module, svc, "onCreateInputView");
        hook(module, svc, "setInputView", android.view.View.class);
        hook(module, svc, "onStartInput", EditorInfo.class, boolean.class);
        hook(module, svc, "onStartInputView", EditorInfo.class, boolean.class);
        hook(module, svc, "onCurrentInputMethodSubtypeChanged", InputMethodSubtype.class);
        hook(module, svc, "onKeyDown", int.class, KeyEvent.class);
        sInstalled = true;
        Log.i(TAG, "service probe installed on " + svc.getName());
    }

    private static void hook(XposedModule module, Class<?> svc, String name, Class<?>... params) {
        try {
            final Method m = svc.getDeclaredMethod(name, params);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                if (BridgeHook.DEV_SERVICE_TRACE) {
                    final Object self = chain.getThisObject();
                    final StringBuilder sb = new StringBuilder("svc ").append(name);
                    sb.append(" impl=").append(self == null ? "?" : self.getClass().getName());
                    for (Object a : chain.getArgs()) {
                        if (a instanceof InputMethodSubtype) {
                            final InputMethodSubtype st = (InputMethodSubtype) a;
                            sb.append(" subtype=").append(st.getLocale()).append('/').append(st.getMode());
                        } else if (a instanceof KeyEvent) {
                            sb.append(" key=").append(((KeyEvent) a).getKeyCode())
                              .append(" ").append(((KeyEvent) a).getAction());
                        } else if (a instanceof EditorInfo) {
                            sb.append(" editor=").append(((EditorInfo) a).inputType);
                        }
                    }
                    Log.i(TAG, sb.toString());
                }
                final Object r = chain.proceed();
                // 配置轮询必须用"输入法服务自己"的 Context：
                // systemContext() 自报包名是 android，provider 会以
                // "Given calling package android does not match caller's uid" 拒绝（踩过）
                if (!sWatchStarted && chain.getThisObject() instanceof android.content.Context) {
                    sWatchStarted = true;
                    ConfigWatch.start((android.content.Context) chain.getThisObject());
                }
                // 顺手把当前的输入连接挂上（严格模式要靠它拦注入的按键）
                try {
                    if (chain.getThisObject() instanceof android.inputmethodservice.InputMethodService) {
                        KeyGuard.installConnection(module,
                                ((android.inputmethodservice.InputMethodService) chain.getThisObject())
                                        .getCurrentInputConnection());
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
        } catch (Throwable tr) {
            Log.w(TAG, "hook " + name + " failed: " + tr);
        }
    }

    private static void warnOnce(String msg) {
        if (sWarned) return;
        sWarned = true;
        Log.w(TAG, msg);
    }
}
