package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard 增强 —— 第一轮：<b>只探测，不改行为</b>。
 *
 * <p>这一版不 hook 任何 Gboard 内部方法，只挂 {@code InputMethodService} 的框架方法
 * （框架类不被混淆，是天然锚点），用来确认：
 * <ol>
 *   <li>IME 服务到底是哪个类（从 {@code getThisObject().getClass()} 读，不写死）；</li>
 *   <li>框架切 subtype 时 {@code onCurrentInputMethodSubtypeChanged} 什么时候来、带什么参数；</li>
 *   <li>硬件按键走不走 {@code onKeyDown}（与 Sogou 一样当"物理键盘"判据用）。</li>
 * </ol>
 *
 * <p>静态分析结论见仓库根目录 {@code ANALYSIS.md}；下一步才用 DexKit 按结构找 Gboard
 * 自己的语言切换器与 InputConnection 包装类。
 */
public class BridgeHook extends XposedModule {

    private static final String TAG = "GboardExt";

    /** 目标输入法。 */
    static final String TARGET_PKG = "com.google.android.inputmethod.latin";

    /** 开发期：打印框架锚点的调用时机（第一轮开着）。 */
    static final boolean DEV_SERVICE_TRACE = true;

    /** 只处理一次（框架可能多次回调同一个包）。 */
    private static final java.util.Set<String> sHandled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public BridgeHook() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (pkg == null || !TARGET_PKG.equals(pkg)) return;   // 本模块是 Gboard 特化
        if (!sHandled.add(pkg)) return;
        Log.i(TAG, "target ready: " + pkg);
        final ClassLoader cl = param.getClassLoader();
        // 引擎类要等输入法起来才加载完；放到后台线程避免拖慢首帧
        final Thread t = new Thread(() -> {
            try {
                ServiceProbe.install(this, cl);
                SwitchGuard.install(this, cl);   // 严格模式：拦掉 Gboard 自己切语言
            } catch (Throwable tr) {
                Log.w(TAG, "probe install failed: " + tr);
            }
        }, "gboard-probe");
        t.setDaemon(true);
        t.start();
    }
}
