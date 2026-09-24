package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把模块 APK 里的 native 库**抽到 Gboard 自己的目录**再 {@code System.load}（离线语音用）。
 *
 * <p><b>为什么不能直接 {@code System.loadLibrary}</b>：库在**模块的** APK 里，而 Gboard 进程的
 * native 搜索路径只有它自己的 lib 目录 —— 官方 AAR 的 Kotlin/Java 类里写死的
 * {@code System.loadLibrary("sherpa-onnx-jni")} 会直接 {@code UnsatisfiedLinkError}。
 * 模块里已有先例（{@code libdexkit.so}，见 {@link ServiceProbe#loadDexKitNative}）：
 * 用 {@code getModuleApplicationInfo().sourceDir} 打开**模块自己的 APK**，按 ABI 取出
 * {@code lib/<abi>/<name>}，写到**宿主（Gboard）**的目录（我们是它的 uid，能写），再
 * {@code System.load(绝对路径)}。好处：受 APK 签名保护、不需要下载、不碰 root。
 *
 * <p><b>放 files 不放 cache</b>：cache 会被系统清掉（19MB 每次会话重抽很浪费）；
 * files 目录不会被自动清，配合"尺寸对得上就跳过"的判断，正常情况下只抽一次。
 *
 * <p><b>加载顺序</b>：{@code libonnxruntime.so} 是 {@code libsherpa-onnx-jni.so} 的依赖，
 * 必须**先**加载 —— Android 链接器不会去"同目录"找依赖，但已加载进进程的 soname 能被后续
 * 加载的主库找到（实测踩过：顺序反了报找不到 onnxruntime）。
 */
final class NativeLibs {

    private static final String TAG = "GboardExt";

    /** 离线语音需要的库（**顺序即加载顺序**：依赖在前）。 */
    private static final String[] OFFLINE = {"libonnxruntime.so", "libsherpa-onnx-jni.so"};

    private static volatile boolean sLoaded;
    private static volatile String sWhy = "未加载";
    private static volatile File sDir;

    private NativeLibs() {
    }

    /** 抽出来的库所在目录（给 sherpa 的 {@code sherpa_onnx.native.path} 用）。 */
    static File dir() {
        return sDir;
    }

    static boolean loaded() {
        return sLoaded;
    }

    static String why() {
        return sWhy;
    }

    /**
     * 幂等：抽 + 加载离线语音的两个库。失败返回 false，原因见 {@link #why()}。
     *
     * @param module 用来拿模块自己的 APK 路径（{@code getModuleApplicationInfo().sourceDir}）
     */
    static synchronized boolean ensureOffline(Context gboardCtx, XposedModule module) {
        if (sLoaded) return true;
        try {
            final String apk = moduleApk(module);
            if (apk == null) {
                sWhy = "拿不到模块 APK 路径";
                Log.w(TAG, "native: " + sWhy);
                return false;
            }
            final String abi = pickAbi();
            final File out = new File(gboardCtx.getFilesDir(), "bzk-libs");
            if (!out.isDirectory() && !out.mkdirs()) {
                sWhy = "建目录失败 " + out;
                return false;
            }
            sDir = out;
            try (ZipFile zf = new ZipFile(apk)) {
                for (String name : OFFLINE) {
                    final File f = new File(out, name);
                    if (!extract(zf, abi, name, f)) {
                        sWhy = "APK 里没有 lib/" + abi + "/" + name;
                        Log.w(TAG, "native: " + sWhy);
                        return false;
                    }
                    System.load(f.getAbsolutePath());          // 顺序即依赖顺序
                    Log.i(TAG, "native: loaded " + name + " (" + f.length() + "B, " + abi + ")");
                }
            }
            sLoaded = true;
            sWhy = "ok";
            return true;
        } catch (Throwable tr) {
            sWhy = String.valueOf(tr);
            Log.w(TAG, "native: 加载失败: " + tr);
            return false;
        }
    }

    private static String moduleApk(XposedModule module) {
        try {
            if (module != null && module.getModuleApplicationInfo() != null) {
                return module.getModuleApplicationInfo().sourceDir;
            }
        } catch (Throwable ignored) {
        }
        try {   // 兜底：classloader 的 CodeSource（LSPosed 下通常是 null）
            final java.security.CodeSource cs =
                    NativeLibs.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) return cs.getLocation().getPath();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从**模块 APK** 里抽一个 asset 出来（VAD 模型走这条：629KiB 直接打包进 APK，
     * 不需要下载也不需要 provider 交付）。已存在且大小一致就跳过。
     */
    static boolean extractAsset(XposedModule module, String assetPath, java.io.File dest) {
        try {
            final String apk = moduleApk(module);
            if (apk == null) return false;
            final java.io.File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            try (ZipFile zf = new ZipFile(apk)) {
                final ZipEntry e = zf.getEntry("assets/" + assetPath);
                if (e == null) return false;
                if (dest.isFile() && dest.length() == e.getSize()) return true;
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream os = new FileOutputStream(dest)) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                return true;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "extractAsset 失败 " + assetPath + ": " + tr);
            return false;
        }
    }

    /** 与 dexkit 那边同一套 ABI 选择逻辑。 */
    private static String pickAbi() {
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if (a.startsWith("arm64")) return "arm64-v8a";
            if (a.startsWith("armeabi")) return "armeabi-v7a";
            if (a.startsWith("x86_64")) return "x86_64";
            if (a.startsWith("x86")) return "x86";
        }
        return android.os.Build.SUPPORTED_ABIS[0];
    }

    /** 抽一个文件；已经存在且尺寸一致就跳过（正常只抽一次）。 */
    private static boolean extract(ZipFile zf, String abi, String name, File out)
            throws Exception {
        final ZipEntry e = zf.getEntry("lib/" + abi + "/" + name);
        if (e == null) return false;
        if (out.isFile() && out.length() == e.getSize()) return true;
        try (InputStream in = zf.getInputStream(e);
             FileOutputStream os = new FileOutputStream(out)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return true;
    }
}
