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

    /**
     * 流式档位的**真机自检**（开发期）：往宿主 files 目录放一份 16k/单声道/16bit 的 WAV
     * （{@code /data/data/com.google.android.inputmethod.latin/files/bzk-test.wav}）就会在装模块时
     * 自动跑一遍流式解码，把部分结果打进日志。
     *
     * <p>为什么值得单独跑：流式那套 {@code OnlineRecognizer} 的 Java API 与 .so 是否对得上
     * **只有真机跑一次才知道** —— 上一轮就吃过"master 的 API 配 v1.13.8 的 .so ⇒ JNI
     * FindMethod 失败 ⇒ SIGABRT 把 Gboard 进程整个带走"。这笔账要在装模块时在后台线程里付，
     * 而不是等用户按下语音键时。
     */
    private static final String STREAM_WAV = "bzk-test.wav";
    private static final String STREAM_MODEL = "zipformer-zh";
    private static final int STREAM_FRAME = 1280;       // 与 AudioSource 一致（40ms）

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
                runStreamingDecode(gboardCtx);
            } catch (Throwable tr) {
                Log.w(TAG, "offline-selftest: ❌ JNI 不通：" + tr);
            }
        }, "bzk-offline-selftest").start();
    }

    /** 有测试 WAV 就跑一遍**流式**解码（走的是生产代码 {@link LocalAsr#feedOnline}）。 */
    private static void runStreamingDecode(final Context ctx) {
        final java.io.File wav = new java.io.File(ctx.getFilesDir(), STREAM_WAV);
        if (!wav.isFile()) return;
        if (!OfflineModels.dirOf(ctx, STREAM_MODEL).isDirectory()) return;   // 模型没同步下来就先跳过
        try {
            final byte[] pcm = pcmOfWav(wav);
            Log.i(TAG, "offline-selftest: 流式自检开始 model=" + STREAM_MODEL
                    + " pcm=" + pcm.length + "B（" + (pcm.length / 32) + "ms）");
            final long t0 = android.os.SystemClock.uptimeMillis();
            LocalAsr.startOnline(ctx, STREAM_MODEL);
            String last = "";
            int frames = 0;
            for (int off = 0; off + STREAM_FRAME <= pcm.length; off += STREAM_FRAME) {
                frames++;
                final String t = LocalAsr.feedOnline(ctx, STREAM_MODEL,
                        java.util.Arrays.copyOfRange(pcm, off, off + STREAM_FRAME));
                if (t != null && !t.isEmpty() && !t.equals(last)) {
                    last = t;
                    Log.i(TAG, "offline-selftest: 流式 partial#" + frames + " \"" + t + "\"");
                }
            }
            final String fin = LocalAsr.finishOnline(ctx, STREAM_MODEL);
            Log.i(TAG, "offline-selftest: ✅ 流式自检完成 " + frames + " 帧 用时 "
                    + (android.os.SystemClock.uptimeMillis() - t0) + "ms -> \"" + fin + "\"");
        } catch (Throwable tr) {
            Log.w(TAG, "offline-selftest: ❌ 流式自检失败: " + tr);
        }
    }

    /**
     * 从 WAV 里取 PCM16 数据。
     *
     * <p>不能假设 data 就在第 44 字节：官方测试 wav 的 fmt 后面还有一个 LIST/INFO 块，
     * 所以这里按 chunk 走（{@code id(4) + size(4) + data(size)}，奇数长度要补一字节）。
     */
    private static byte[] pcmOfWav(java.io.File f) throws Exception {
        final byte[] all = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n = 0;
            while (n < all.length) {
                final int r = in.read(all, n, all.length - n);
                if (r < 0) break;
                n += r;
            }
        }
        int p = 12;
        while (p + 8 <= all.length) {
            final String id = new String(all, p, 4, "US-ASCII");
            final int size = (all[p + 4] & 0xff) | ((all[p + 5] & 0xff) << 8)
                    | ((all[p + 6] & 0xff) << 16) | ((all[p + 7] & 0xff) << 24);
            if ("data".equals(id)) {
                final int len = Math.min(size, all.length - p - 8);
                return java.util.Arrays.copyOfRange(all, p + 8, p + 8 + len);
            }
            p += 8 + size + (size & 1);
        }
        throw new Exception("WAV 里没有 data 块");
    }
}
