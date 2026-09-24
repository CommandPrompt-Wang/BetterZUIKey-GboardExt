package moe.lovefirefly.bzk.gboardext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 离线模型的**下载服务**（App 侧）。
 *
 * <p><b>为什么是前台服务 + 通知</b>：模型 78–228MB，下载要几分钟；普通后台线程会被系统随时掐掉，
 * 而且用户需要一个进度可见的地方（实测 Gboard 没声明 POST_NOTIFICATIONS，通知只能由 App 发）。
 * 所以：{@code dataSync} 类型前台服务 + 通知栏进度条，下载完再补一条"已就绪"通知。
 *
 * <p><b>断点续传</b>：每个文件先下到 {@code <name>.part}，用 {@code Range: bytes=<已有>-} 续；
 * 服务端不支持续传（返回 200）就从头来。文件下完按**精确字节数**校验，清单里带 sha256 时再校验哈希。
 *
 * <p><b>模型放哪</b>：App 自己的 {@code files/models/<id>/}。Gboard 读不了这里（实测 EACCES），
 * 所以注入侧要用时得经 ContentProvider 拉一份过去 —— 那是下一步（见 local/plan.md §19.4 方案 B）。
 */
public class ModelDownloadService extends Service {

    private static final String TAG = "GboardExt";

    static final String ACTION_START = "moe.lovefirefly.bzk.gboardext.action.DOWNLOAD_MODEL";
    static final String ACTION_CANCEL = "moe.lovefirefly.bzk.gboardext.action.CANCEL_DOWNLOAD";
    static final String EXTRA_ID = "modelId";

    private static final String CHANNEL = "offline-model";
    private static final int NOTIF_PROGRESS = 0x0F51;
    private static final int NOTIF_DONE = 0x0F52;

    private static volatile boolean sRunning;

    private volatile boolean cancelled;
    private Thread worker;

    /** 启动下载（外部唯一入口）。 */
    static void start(Context c, String id) {
        final Intent i = new Intent(c, ModelDownloadService.class)
                .setAction(ACTION_START).putExtra(EXTRA_ID, id);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable tr) {
            Log.w(TAG, "model download start failed: " + tr);
        }
    }

    static boolean busy() {
        return sRunning;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        final String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        final VoiceModels.Model m = id == null ? null : VoiceModels.byId(id);
        if (m == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (sRunning) {
            Log.i(TAG, "model download already running, ignore " + id);
            return START_NOT_STICKY;
        }
        sRunning = true;
        cancelled = false;
        channel();
        startForeground(NOTIF_PROGRESS, notif(m, 0, true));
        VoiceModels.setState(this, m.id, VoiceModels.STATE_DOWNLOADING);
        VoiceModels.setProgress(this, m.id, 0);
        worker = new Thread(() -> runDownload(m), "bzk-model-download");
        worker.setDaemon(true);
        worker.start();
        return START_NOT_STICKY;      // 被杀就停：用户可以再点一次（已下载的部分会续传）
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 下载

    private void runDownload(VoiceModels.Model m) {
        String fail = null;
        try {
            final File dir = VoiceModels.dirOf(this, m);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("建目录失败 " + dir);
            final long total = m.totalBytes();
            long done = 0;
            for (VoiceModels.FileSpec f : m.files) {
                done += alreadyHave(dir, f);
            }
            // ① 优先**镜像分片**（Cloudflare 单文件上限 25MiB ⇒ 清单里是 <模型>.zip.00N）：
            //    逐片下 → 拼装 → 按 archive 的 sha-256 校验 → 解包；下面的逐文件循环随即跳过。
            //    拿不到清单/片下不下来 ⇒ ② 退回按文件从上游直链下（hf-mirror / GitHub）。
            boolean mirrorOk = false;
            try {
                mirrorOk = downloadFromMirror(m, dir);
            } catch (Throwable tr) {
                if (cancelled) throw tr;            // 用户取消：别再去试上游
                Log.w(TAG, "model " + m.id + " 镜像下载失败，改走上游直链：" + tr);
                deleteTree(dir);                    // 别把半份文件混进后面的校验
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            for (VoiceModels.FileSpec f : m.files) {
                if (mirrorOk) break;                // 镜像那条路已经把文件摆好了
                final File part = new File(dir, f.name + ".part");
                long have = part.isFile() ? part.length() : 0;
                if (have > f.bytes) {                       // 上次留下的脏数据
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    have = 0;
                }
                if (have == f.bytes) {                      // 这个文件已经下完
                    rename(part, new File(dir, f.name));
                    continue;
                }
                boolean ok = false;
                Throwable last = null;
                for (String url : f.urls) {
                    try {
                        have = fetch(url, part, f.bytes, m, () -> progressOf(dir, m, total));
                        ok = true;
                        break;
                    } catch (Throwable tr) {
                        last = tr;
                        Log.w(TAG, "model " + m.id + " url failed: " + url + " -> " + tr);
                        if (cancelled) break;
                    }
                }
                if (cancelled) throw new Exception("已取消");
                if (!ok) {
                    // 兜底：APK 里带了一份同样的文件（silero_vad 只发在 GitHub Releases，
                    // 国内不一定直连）—— 拷过去，校验步骤照旧
                    if (copyFromAssets(this, "models/" + f.name, part)) {
                        Log.i(TAG, "model " + m.id + " " + f.name + " 从 APK 内置副本拷贝");
                        ok = true;
                    }
                }
                if (!ok) throw new Exception(f.name + " 下载失败：" + last);
                rename(part, new File(dir, f.name));
            }
            // 全下完之后统一校验（体积 + 可选哈希）
            for (VoiceModels.FileSpec f : m.files) {
                final File fp = new File(dir, f.name);
                if (!fp.isFile() || fp.length() != f.bytes) {
                    throw new Exception(f.name + " 体积不符：" + (fp.isFile() ? fp.length() : -1)
                            + " != " + f.bytes);
                }
                if (!f.sha256.isEmpty()) {
                    final String got = sha256(fp);
                    if (!f.sha256.equalsIgnoreCase(got)) {
                        throw new Exception(f.name + " 哈希不符");
                    }
                }
            }
            VoiceModels.setState(this, m.id, VoiceModels.STATE_READY);
            VoiceModels.setProgress(this, m.id, 100);
            Log.i(TAG, "model ready: " + m.id + " (" + total + " B)");
            // 通知宿主"模型就绪 + 版本"，它会顺手把权重拷进自己的存储（不用等下次开会话）
            ConfigSender.sendAndRetry(this);
        } catch (Throwable tr) {
            fail = String.valueOf(tr.getMessage() == null ? tr : tr.getMessage());
            Log.w(TAG, "model download failed: " + tr);
            VoiceModels.setState(this, m.id, "");
            VoiceModels.setProgress(this, m.id, -1);
        } finally {
            sRunning = false;
            stopForeground(true);
            final NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                if (fail == null) {
                    nm.notify(NOTIF_DONE, done(m));
                } else {
                    nm.notify(NOTIF_DONE, failed(m, fail));
                }
            }
            stopSelf();
        }
    }

    /** 已经完整下载好的文件（用于进度起点）。 */
    private static long alreadyHave(File dir, VoiceModels.FileSpec f) {
        final File done = new File(dir, f.name);
        if (done.isFile() && done.length() == f.bytes) return f.bytes;
        final File part = new File(dir, f.name + ".part");
        return part.isFile() ? Math.min(part.length(), f.bytes) : 0;
    }

    /** 当前总进度（字节）。 */
    private static long progressOf(File dir, VoiceModels.Model m, long total) {
        long n = 0;
        for (VoiceModels.FileSpec f : m.files) n += alreadyHave(dir, f);
        return Math.min(n, total);
    }

    /**
     * 下载一个文件（可续传）。
     *
     * @return 下完后的实际长度
     */
    private long fetch(String url, File part, long expect, VoiceModels.Model m,
            java.util.function.LongSupplier progress) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            final int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new Exception("HTTP " + code);
            if (code == 200) {                     // 服务端不支持续传 ⇒ 从头来
                have = 0;
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            long lastNotify = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream os = new FileOutputStream(part, have > 0)) {
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new Exception("已取消");
                    os.write(buf, 0, n);
                    have += n;
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastNotify > 400) {  // 通知栏别刷太勤
                        lastNotify = now;
                        final int pct = (int) Math.min(99, progress.getAsLong() * 100
                                / Math.max(1, m.totalBytes()));
                        VoiceModels.setProgress(this, m.id, pct);
                        final NotificationManager nm = getSystemService(NotificationManager.class);
                        if (nm != null) nm.notify(NOTIF_PROGRESS, notif(m, pct, false));
                    }
                }
            }
            return have;
        } finally {
            conn.disconnect();
        }
    }

    /** 从 APK 的 assets 里拷一份（下载源不通时的兜底）。 */
    private static boolean copyFromAssets(Context c, String asset, File dest) {
        try (InputStream in = c.getAssets().open(asset);
             FileOutputStream os = new FileOutputStream(dest)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "assets 兜底失败 " + asset + ": " + tr);
            return false;
        }
    }

    private static void rename(File from, File to) {
        //noinspection ResultOfMethodCallIgnored
        to.delete();
        //noinspection ResultOfMethodCallIgnored
        from.renameTo(to);
    }

    private static String sha256(File f) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16))
              .append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 通知

    private void channel() {
        if (Build.VERSION.SDK_INT < 26) return;
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        final NotificationChannel ch = new NotificationChannel(CHANNEL, "离线语音模型",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notif(VoiceModels.Model m, int pct, boolean indeterminate) {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Intent cancel = new Intent(this, ModelDownloadService.class).setAction(ACTION_CANCEL);
        final PendingIntent pc = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载 " + m.label)
                .setContentText(pct + "%（" + m.sizeText() + "）")
                .setProgress(100, pct, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "取消", pc).build())
                .build();
    }

    private Notification done(VoiceModels.Model m) {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(m.label + " 已就绪")
                .setContentText("离线语音可在没有网络时使用")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
    }

    private Notification failed(VoiceModels.Model m, String why) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(m.label + " 下载失败")
                .setContentText(why)
                .setAutoCancel(true)
                .build();
    }

    // ------------------------------------------------------------------ 镜像（分片 zip）

    /** 分片下载的工作目录（拼装完就删）。 */
    private File partsDir(VoiceModels.Model m) {
        return new File(getFilesDir(), "parts/" + m.id);
    }

    /**
     * 按镜像清单下载**分片 zip**：逐片（可续传）→ 顺序拼装 → 校验 archive 的 size+sha256 →
     * 解包（扁平）到模型目录。
     *
     * @return false = 清单里没有这一档（调用方退回上游直链）
     */
    private boolean downloadFromMirror(VoiceModels.Model m, File dir) throws Exception {
        final org.json.JSONObject entry = fetchManifestEntry(m.id);
        if (entry == null) return false;
        final org.json.JSONArray parts = entry.optJSONArray("files");
        final org.json.JSONObject archive = entry.optJSONObject("archive");
        if (parts == null || parts.length() == 0 || archive == null) {
            Log.i(TAG, "model " + m.id + "：清单里没有分片信息，走上游直链");
            return false;
        }
        final String zipName = archive.optString("name", m.id + ".zip");
        final long zipBytes = archive.optLong("size", 0);
        final String zipSha = archive.optString("sha-256", "");

        final File work = partsDir(m);
        deleteTree(work);
        if (!work.isDirectory() && !work.mkdirs()) throw new Exception("建目录失败 " + work);
        try {
            final java.util.List<File> got = new java.util.ArrayList<>();
            final long[] done = { 0 };
            for (int i = 0; i < parts.length(); i++) {
                final org.json.JSONObject p = parts.getJSONObject(i);
                final String name = p.getString("name");
                final long bytes = p.getLong("size");
                final String sha = p.optString("sha-256", "");
                // 清单里的 link 是**站根相对**路径（与站点其它资源一致）⇒ 以后镜像挪目录不用改 App
                final String rel = p.optString("link", "");
                final String url = rel.isEmpty()
                        ? VoiceModels.MIRROR + m.id + "/" + name
                        : VoiceModels.MIRROR_HOST + rel;
                final File part = new File(work, name + ".part");
                final File fin = new File(work, name);
                if (fin.isFile() && fin.length() == bytes) {     // 断点续传：这一片已经好了
                    got.add(fin);
                    done[0] += bytes;
                    continue;
                }
                final long before = done[0];
                fetch(url, part, bytes, m,
                        () -> before + (part.isFile() ? part.length() : 0));
                if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(part))) {
                    throw new Exception(name + " 哈希不符");
                }
                rename(part, fin);
                got.add(fin);
                done[0] = before + bytes;
                Log.i(TAG, "model " + m.id + "：分片 " + (i + 1) + "/" + parts.length()
                        + " 完成（" + bytes + "B）");
            }
            // 顺序拼装成 zip，按清单里的 archive 校验，再解包
            final File zip = new File(work, zipName);
            concat(got, zip);
            if (zipBytes > 0 && zip.length() != zipBytes) {
                throw new Exception("拼装后体积不符：" + zip.length() + " != " + zipBytes);
            }
            if (!zipSha.isEmpty() && !zipSha.equalsIgnoreCase(sha256(zip))) {
                throw new Exception("拼装后哈希不符（zip）");
            }
            unzipFlat(zip, dir, m);
            Log.i(TAG, "model " + m.id + "：镜像 " + parts.length() + " 片 → " + zipName
                    + "（" + zip.length() + "B）解包完成");
            return true;
        } finally {
            deleteTree(work);
        }
    }

    /** 取清单里对应档位的条目（清单拿不到/没有这一档都返回 null ⇒ 走上游直链）。 */
    private org.json.JSONObject fetchManifestEntry(String id) {
        try {
            final HttpURLConnection conn = (HttpURLConnection)
                    new URL(VoiceModels.MIRROR_MANIFEST).openConnection();
            try {
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                final int code = conn.getResponseCode();
                if (code != 200) {
                    Log.i(TAG, "镜像清单 HTTP " + code + "，走上游直链");
                    return null;
                }
                final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                }
                final org.json.JSONObject all =
                        new org.json.JSONObject(new String(bos.toByteArray(), "UTF-8"));
                final org.json.JSONObject e = all.optJSONObject(id);
                if (e == null) Log.i(TAG, "镜像清单里没有 " + id + "，走上游直链");
                return e;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable tr) {
            Log.i(TAG, "取镜像清单失败（走上游直链）：" + tr);
            return null;
        }
    }

    /** 顺序拼接分片。 */
    private static void concat(java.util.List<File> parts, File out) throws Exception {
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
            final byte[] buf = new byte[1 << 16];
            for (File f : parts) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    /** 解包**扁平** zip（只接受清单里声明过的文件名，防目录穿越）到模型目录。 */
    private void unzipFlat(File zip, File dir, VoiceModels.Model m) throws Exception {
        final java.util.Set<String> want = new java.util.HashSet<>();
        for (VoiceModels.FileSpec f : m.files) want.add(f.name);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                final String name = e.getName();
                if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
                    throw new Exception("zip 里有可疑路径：" + name);
                }
                if (!want.contains(name)) {
                    Log.i(TAG, "model " + m.id + "：zip 里的 " + name + " 不在清单里，跳过");
                    continue;
                }
                try (java.io.FileOutputStream os =
                             new java.io.FileOutputStream(new File(dir, name))) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    /** 递归删除（镜像分片的工作目录 / 镜像失败后清空模型目录）。 */
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
