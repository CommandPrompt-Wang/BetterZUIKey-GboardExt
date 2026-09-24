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
            for (VoiceModels.FileSpec f : m.files) {
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
}
