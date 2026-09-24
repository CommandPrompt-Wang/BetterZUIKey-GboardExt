package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 离线引擎的**加载自检**：在 Gboard 进程里把 sherpa-onnx 的两个 native 库抽出来、加载、
 * 再问一次版本号。
 *
 * <p>为什么要专门自检：这条链路有两个"能不能成"的未知数 ——
 * ① Gboard 进程能不能 {@code System.load} 我们从模块 APK 抽出来的库（先例是 libdexkit ✔）；
 * ② **官方 Java API 的 native 声明与官方 Android JNI 库是否对得上**（Java API 是给桌面 JVM 用的，
 * 而 .so 是从 Android AAR 里取的）。第 ② 条只有真机跑一次才知道，所以这里在装模块时就跑一遍，
 * 成功会打 {@code offline-selftest: ✅ sherpa=... onnxruntime=...}。
 *
 * <p>失败一律只记日志、不抛（不能因为离线功能没就绪影响别的功能）。
 */
final class OfflineSelfTest {

    private static final String TAG = "GboardExt";

    /** 开发期开关：装模块时跑一次自检。跑通后可以关掉（关掉只省一次 27MB 的抽取）。 */
    static final boolean DEV_SELFTEST = true;

    private OfflineSelfTest() {
    }

    static void run(final Context gboardCtx, final XposedModule module) {
        if (!DEV_SELFTEST) return;
        new Thread(() -> {
            try {
                Log.i(TAG, "offline-selftest: 开始（uid=" + android.os.Process.myUid() + "）");
                if (!NativeLibs.ensureOffline(gboardCtx, module)) {
                    Log.w(TAG, "offline-selftest: ❌ 库没加载上：" + NativeLibs.why());
                    return;
                }
                // 关掉它的自动加载：库已经由 NativeLibs 用绝对路径 load 过了，
                // 否则 AAR/API 里写死的 System.loadLibrary 会去 Gboard 自己的 lib 目录找、必然找不到。
                com.k2fsa.sherpa.onnx.LibraryLoader.setAutoLoadEnabled(false);
                final String v = com.k2fsa.sherpa.onnx.VersionInfo.getVersion();
                final String ort = com.k2fsa.sherpa.onnx.VersionInfo.getOnnxruntimeVersion();
                Log.i(TAG, "offline-selftest: ✅ sherpa=" + v + " onnxruntime=" + ort);
            } catch (Throwable tr) {
                Log.w(TAG, "offline-selftest: ❌ JNI 不通：" + tr);
            }
        }, "bzk-offline-selftest").start();
    }
}
