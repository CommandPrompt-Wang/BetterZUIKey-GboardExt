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
 * <p><b>只在中文态生效</b>：判据是当前 subtype 的 locale（{@link ServiceProbe#isChinese()}）。
 * 日语有完善的候选/组合框，一律不碰；英文态本来就是半角，改了也是空转。
 *
 * <p>组合态（{@code setComposingText}）也挂上：中文态下符号通常直接上屏，但拼音字母
 * 的组合态也会经过这里 —— 表里没有 ASCII 字母，所以那些字符天然不会被改。
 */
final class SymbolNormHook {

    private static final String TAG = "GboardExt";

    /** 开发期：打印每次被改写的提交对。 */
    static final boolean DEV_TRACE = true;

    private static volatile boolean sInstalled;

    private SymbolNormHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        if (SymbolNorm.size() == 0) {
            // 默认开：不开 App 也应该按默认表工作（广播只是把它改成用户的设置）
            SymbolNorm.setTable(SymbolNorm.DEFAULT_TABLE);
        }
        final Class<?> cls;
        try {
            cls = Class.forName("android.inputmethodservice.RemoteInputConnection", false, cl);
        } catch (Throwable tr) {
            Log.w(TAG, "norm: RemoteInputConnection not found: " + tr);
            return;
        }
        hook(module, cls, "commitText", CharSequence.class, int.class);
        hook(module, cls, "setComposingText", CharSequence.class, int.class);
        Log.i(TAG, "norm hooked " + cls.getName());
    }

    private static void hook(XposedModule module, Class<?> cls, String name, Class<?>... params) {
        try {
            final Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a0 = chain.getArg(0);
                if (!(a0 instanceof CharSequence)) return chain.proceed();
                if (!ServiceProbe.isChinese()) return chain.proceed();
                final String out = SymbolNorm.apply((CharSequence) a0);
                if (out == null) return chain.proceed();
                if (DEV_TRACE) Log.i(TAG, "norm " + name + ": " + a0 + " -> " + out);
                final Object[] args = chain.getArgs().toArray();
                args[0] = out;
                return chain.proceed(args);
            });
            Log.i(TAG, "norm hooked " + cls.getSimpleName() + "#" + name);
        } catch (Throwable tr) {
            Log.w(TAG, "norm hook " + name + " failed: " + tr);
        }
    }
}
