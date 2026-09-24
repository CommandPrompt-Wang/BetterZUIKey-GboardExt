package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 离线语音（本地 ASR）落地前的一次性存储探针：**在 Gboard 进程里**回答
 * "模型权重放哪、能不能跨 App 读" 这个问题。
 *
 * <p><b>为什么要专门探</b>：离线模型的权重有几百 MB，绝不能进 APK，只能下载。而下载到哪里
 * 决定了整个架构 —— 只有三种可能：
 * <ol>
 *   <li>下载进 Gboard 自己的 files 目录（我们是它的 uid，写自己家没问题）⇒ 下载必须发生在
 *       Gboard 进程里；</li>
 *   <li>下载进模块 App 的目录，Gboard 侧**直接按路径读**（零拷贝，最省事）⇒ 需要跨 App 可读；</li>
 *   <li>下载进模块 App 的目录，Gboard 侧**拷一份**进自己家（走 ContentProvider）⇒ 峰值占双份。</li>
 * </ol>
 * Android 11+ 的分区存储里，{@code /sdcard/Android/media/<别的包>/} 是否对第三方 App 可读写
 * 只能实测（文档说法含糊，各家实现也未必一致），所以这里逐个试、把**原始异常**打出来。
 *
 * <p>只读不改：所有写入都在探针目录里，跑完即删。默认关（{@link #DEV_STORAGE_PROBE}）。
 *
 * <p><b>2026-09-24 实测结论（arm64 平板，API 36，Gboard uid 10304）</b>：
 * <pre>
 *   Gboard 自己的 files/            写 ✔ 读 ✔ 删 ✔（权重只能落这里）
 *   模块 App 的 Android/media/…     看得见（exists=true, len 对）但读不了：EACCES
 *                                   写更不行：EPERM（FUSE 直接拒）
 *   模块 App 的 Android/data/…      mkdirs 失败
 *   /data/local/tmp/               mkdirs 失败
 * </pre>
 * ⇒ "App 下载、Gboard 按路径直接读"（零拷贝）**不通**；跨进程只能走 ContentProvider 拿 FD
 *   （模块里已有先例：ConfigProvider，Gboard 侧用 ContentResolver.query 读配置）。
 *   详见 local/plan.md §19。
 */
final class StorageProbe {

    private static final String TAG = "GboardExt";

    /** 开发期一次性开关：打开后模块加载时会在 Gboard 进程里跑一遍存储探测。 */
    static final boolean DEV_STORAGE_PROBE = false;

    /** 开发期开关：App 侧往自己的 Android/media 写一个测试文件（供 Gboard 侧读）。 */
    static final boolean DEV_STORAGE_PROBE_APP = false;

    /** App 侧写、Gboard 侧读的那个文件名（两边约定）。 */
    private static final String CROSS_FILE = "fromapp.bin";

    private StorageProbe() {
    }

    /**
     * **App 侧**：往自己的 {@code Android/media/<自己>} 写一个 64KB 文件。
     *
     * <p>用来回答"App 下载、Gboard 直接读"这条零拷贝路线到底行不行 —— Gboard 侧能不能**读**别的
     * App 放在自己 media 目录里的文件（FUSE 通常只拦写、放读）。不需要任何存储权限。
     */
    static void writeFromApp(final Context appCtx) {
        if (!DEV_STORAGE_PROBE_APP) return;
        new Thread(() -> {
            try {
                final File[] dirs = appCtx.getExternalMediaDirs();
                final File base = dirs != null && dirs.length > 0
                        ? dirs[0] : new File("/sdcard/Android/media/" + appCtx.getPackageName());
                if (!base.exists() && !base.mkdirs()) {
                    Log.w(TAG, "storage-probe(app): 建目录失败 " + base);
                    return;
                }
                final File f = new File(base, CROSS_FILE);
                final byte[] data = new byte[64 * 1024];
                for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                Log.i(TAG, "storage-probe(app): 写入 OK " + f.getAbsolutePath()
                        + " " + f.length() + "B 可读=" + f.canRead());
                // 顺便把模组自己的探针目录清掉（上一轮 Gboard 侧想写没写成的残留）
                new File(base, "bzk-probe").delete();
            } catch (Throwable tr) {
                Log.w(TAG, "storage-probe(app): ✗ " + tr);
            }
        }, "bzk-storage-probe-app").start();
    }

    static void run(final Context gboardCtx) {
        if (!DEV_STORAGE_PROBE) return;
        final String self = "moe.lovefirefly.bzk.gboardext";
        new Thread(() -> {
            Log.i(TAG, "storage-probe: ===== 开始（进程 uid=" + android.os.Process.myUid() + "）=====");
            // 对照组：自己的家（必然可以）
            probe("自己 files/", new File(gboardCtx.getFilesDir(), "bzk-probe"));
            // **关键一测**：读"App 自己写进它 media 目录"的文件（零拷贝方案成不成看这一条）
            readCross(self);
            // 目标一：模块 App 的 Android/media（跨 App 共享媒体目录，零拷贝方案的关键）
            probe("模块 media/", new File("/sdcard/Android/media/" + self + "/bzk-probe"));
            // 目标二：模块 App 的 Android/data（预期被分区存储挡住，用作对照）
            probe("模块 data/", new File("/sdcard/Android/data/" + self + "/files/bzk-probe"));
            // 目标三：shell 的临时目录（root 推文件的常见落点）
            probe("/data/local/tmp/", new File("/data/local/tmp/bzk-probe"));
            Log.i(TAG, "storage-probe: ===== 结束 =====");
        }, "bzk-storage-probe").start();
    }

    /**
     * 读"App 自己写进它 media 目录"的文件 —— 跨 App 读。零拷贝方案成不成，全看这一条。
     * （写会被 FUSE 挡（已实测 EPERM），但读往往放行。）
     */
    private static void readCross(String modulePkg) {
        final File f = new File("/sdcard/Android/media/" + modulePkg, CROSS_FILE);
        final StringBuilder sb = new StringBuilder("storage-probe: 跨App读 ")
                .append(f.getAbsolutePath());
        try {
            sb.append(" exists=").append(f.exists()).append(" canRead=").append(f.canRead())
                    .append(" len=").append(f.length());
            try (FileInputStream in = new FileInputStream(f)) {
                final byte[] buf = new byte[64 * 1024];
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                boolean ok = off == buf.length;
                for (int i = 0; ok && i < off; i++) {
                    if (buf[i] != (byte) (i * 7)) ok = false;
                }
                sb.append(" 读回 ").append(off).append("B 内容")
                        .append(ok ? "一致 ✔" : "不一致 ✗");
            }
        } catch (Throwable tr) {
            sb.append(" | ✗ ").append(tr);
        }
        Log.i(TAG, sb.toString());
    }

    private static void probe(String label, File dir) {        final StringBuilder sb = new StringBuilder("storage-probe: ").append(label)
                .append(dir.getAbsolutePath());
        try {
            sb.append(" exists=").append(dir.exists());
            sb.append(" canRead=").append(dir.canRead()).append(" canWrite=").append(dir.canWrite());
            if (!dir.exists() && !dir.mkdirs()) {
                sb.append(" | mkdirs 失败(父层=")
                        .append(dir.getParentFile() != null && dir.getParentFile().canWrite())
                        .append(")");
                Log.i(TAG, sb.toString());
                return;
            }
            final File f = new File(dir, "p.bin");
            final byte[] data = new byte[64 * 1024];
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);                       // 写
            }
            sb.append(" | 写 OK ").append(f.length()).append("B");
            try (FileInputStream in = new FileInputStream(f)) {   // 读回
                final byte[] back = new byte[data.length];
                int off = 0;
                while (off < back.length) {
                    final int n = in.read(back, off, back.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                sb.append(" 读回 ").append(off).append("B");
            }
            sb.append(" 删除=").append(f.delete());
        } catch (Throwable tr) {
            sb.append(" | ✗ ").append(tr);            // 原始异常（含 SELinux/EACCES 细节）
        }
        Log.i(TAG, sb.toString());
    }
}
