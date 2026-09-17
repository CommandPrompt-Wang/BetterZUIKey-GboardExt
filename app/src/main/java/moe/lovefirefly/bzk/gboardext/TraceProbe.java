package moe.lovefirefly.bzk.gboardext;

import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 诊断探针：把"点软键盘地球键时到底调了什么"全部打出来。
 *
 * <p>前几轮都是"猜路径再挂"，结果是 0 命中；这轮改成全量：IMM / InputMethodService 的
 * <b>每个方法</b>、IMMS 代理的每次调用、以及 {@code BinderProxy.transact}（带接口描述符）。
 * 一次按键就能看出真实路径。
 *
 * <p>只记日志、一律放行，不改行为。诊断完把 {@link #ON} 关掉。
 */
final class TraceProbe {

    private static final String TAG = "GboardExt";
    static final boolean ON = true;

    private static volatile boolean sInstalled;

    private TraceProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled || !ON) return;
        sInstalled = true;
        hookAll(module, cl, "android.view.inputmethod.InputMethodManager", "IMM");
        hookAll(module, cl, "android.inputmethodservice.InputMethodService", "IMS");
        hookBinderProxy(module, cl);
        Log.i(TAG, "trace probe installed");
    }

    /** 把某个框架类声明的每个方法都挂上（抽象/无实体方法会抛，忽略即可）。 */
    private static void hookAll(XposedModule module, ClassLoader cl, String cn, String label) {
        int n = 0;
        try {
            final Class<?> c = Class.forName(cn, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        Log.i(TAG, "TRACE " + label + "." + m.getName());
                        return chain.proceed();
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "trace: " + cn + " -> " + n + " method(s)");
        } catch (Throwable tr) {
            Log.w(TAG, "trace hookAll " + cn + " failed: " + tr);
        }
    }

    /** 每一次跨进程调用（接口描述符 + 事务码），用来抓"系统代它切"的那条路。 */
    private static void hookBinderProxy(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> c = Class.forName("android.os.BinderProxy", false, cl);
            final Method m = c.getDeclaredMethod("transact",
                    int.class, Parcel.class, Parcel.class, int.class);
            m.setAccessible(true);
            final Method desc = c.getDeclaredMethod("getInterfaceDescriptor");
            desc.setAccessible(true);
            module.hook(m).intercept(chain -> {
                String d = "?";
                try {
                    final Object r = desc.invoke(chain.getThisObject());
                    if (r instanceof String) d = (String) r;
                } catch (Throwable ignored) {
                }
                Log.i(TAG, "TRACE binder " + d + " code=" + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, "trace: BinderProxy#transact hooked");
        } catch (Throwable tr) {
            Log.w(TAG, "trace binder failed: " + tr);
        }
    }
}
