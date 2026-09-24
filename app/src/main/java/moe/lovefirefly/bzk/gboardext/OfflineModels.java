package moe.lovefirefly.bzk.gboardext;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Gboard 侧的**离线模型缓存管理**（见 local/plan.md §23）。
 *
 * <p><b>语义（用户口径）</b>：
 * <ul>
 *   <li>只缓存**当前选中的那一个**模型 —— 切换选择就把旧的目录删掉；</li>
 *   <li>取消选择/删除模型（广播里 model 为空）⇒ 删掉本地缓存；</li>
 *   <li>版本不符（App 重新下载过、或换了模型）⇒ 重拉，先写 {@code .part} 再改名。</li>
 * </ul>
 *
 * <p><b>字节从哪来</b>：Gboard 读不了 App 的文件（实测 EACCES），只能经
 * {@code ModelProvider} 拿 FD 读（{@link ContentResolver#openInputStream}）。
 * provider 由系统按需拉起 App 进程，所以**不需要 App 常驻**。
 *
 * <p><b>放哪</b>：{@code <Gboard 的 filesDir>/bzk-models/<模型id>/}（自己的存储，读自己没问题），
 * 旁边写一个 {@code version.txt} 记录版本号，用来判断"要不要重拉"。
 */
final class OfflineModels {

    private static final String TAG = "GboardExt";

    private static final String AUTHORITY = "moe.lovefirefly.bzk.gboardext.model";
    private static final String DIR = "bzk-models";
    private static final String VERSION_FILE = "version.txt";
    /** 「补全标点」模型的目录名（附加共享件，不参与"只留当前 ASR 模型"的清扫）。 */
    private static final String PUNCT_DIR = "punct";

    private static volatile boolean sBusy;
    private static volatile String sLastInfo = "";

    private OfflineModels() {
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    static File dirOf(Context c, String id) {
        return new File(root(c), id);
    }

    /** 当前本地缓存的是哪个模型（没有则空串）。 */
    static String cachedId(Context c) {
        final File[] kids = root(c).listFiles();
        if (kids == null) return "";
        for (File d : kids) {
            if (d.isDirectory()) return d.getName();
        }
        return "";
    }

    static String cachedVersion(Context c, String id) {
        final File f = new File(dirOf(c, id), VERSION_FILE);
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            final byte[] b = new byte[(int) Math.min(128, f.length())];
            final int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable tr) {
            return "";
        }
    }

    /** 本地缓存是否可用（目录在 + 版本对得上 + 清单里的文件都在）。 */
    static boolean ready(Context c, String id, String version) {
        if (id == null || id.isEmpty()) return false;
        if (!version.equals(cachedVersion(c, id))) return false;
        final File[] kids = dirOf(c, id).listFiles();
        return kids != null && kids.length > 0;
    }

    static String info(Context c) {
        final String id = cachedId(c);
        if (id.isEmpty()) return "";
        final File d = dirOf(c, id);
        long bytes = 0;
        final File[] kids = d.listFiles();
        if (kids != null) {
            for (File f : kids) bytes += f.length();
        }
        return id + "|" + bytes;
    }

    /**
     * 按广播里的（模型 id + 版本）对齐本地缓存。**异步**，失败只记日志 + 回传状态。
     *
     * @param id      选中的模型 id；空 = 取消/删除 ⇒ 清缓存
     * @param version 版本号（App 侧算出来的）；空 = 不校验版本（仅清理）
     */
    static void sync(final Context gboardCtx, final String id, final String version) {
        if (gboardCtx == null) return;
        if (id == null || id.isEmpty()) {
            new Thread(() -> {
                final String old = cachedId(gboardCtx);
                if (!old.isEmpty()) {
                    deleteTree(dirOf(gboardCtx, old));
                    Log.i(TAG, "offline: 缓存已清（" + old + "）");
                }
                report(gboardCtx);
            }, "bzk-offline-clear").start();
            return;
        }
        if (sBusy) {
            Log.i(TAG, "offline: 已有同步在进行，忽略 " + id);
            return;
        }
        sBusy = true;
        new Thread(() -> {
            try {
                // ① 只留当前这一个
                final File[] kids = root(gboardCtx).listFiles();
                if (kids != null) {
                    for (File d : kids) {
                        // punct/ 是"补全标点"的附加共享件，不能被"只留当前 ASR 模型"的清扫删掉
                        if (d.isDirectory() && !d.getName().equals(id)
                                && !d.getName().equals(PUNCT_DIR)) {
                            deleteTree(d);
                            Log.i(TAG, "offline: 切换模型，删掉旧缓存 " + d.getName());
                        }
                    }
                }
                // ② 版本一致就不用拉
                if (ready(gboardCtx, id, version)) {
                    Log.i(TAG, "offline: 本地缓存已是最新 " + id + "@" + version);
                    report(gboardCtx);
                    return;
                }
                // ③ 拉取
                pull(gboardCtx, id, version);
                report(gboardCtx);
                // 模型刚落地的这一份是**热**的：后台先加载好（24MiB 档 ≈2.3s、189MiB 档 ≈7.5s）。
                // 不预热的话，用户第一次按语音键要等好几秒，而且那几秒的音频只能丢（见 LocalAsr）。
                LocalAsr.preload(gboardCtx, id);
                // 预热会占内存（189MiB 档能到 ~190MB）：按"空闲 60s 自动释放"这条规矩排一次释放
                VoiceEngineHost.scheduleIdleRelease();
            } catch (Throwable tr) {
                Log.w(TAG, "offline: 同步失败: " + tr);
                report(gboardCtx);
            } finally {
                sBusy = false;
            }
        }, "bzk-offline-sync").start();
    }

    /** 标点模型目录。 */
    static File punctDir(Context c) {
        return new File(root(c), PUNCT_DIR);
    }

    /**
     * 「补全标点」：把标点模型当**附加共享件**交付（开=按版本拉取，关/删=清掉）。
     *
     * <p>它与 ASR 模型的区别：不参与"只缓存当前选中那一个"的规则（多个 ASR 模型共用同一份标点）。
     */
    static void syncPunct(final Context c, final boolean enabled, final String version) {
        if (c == null) return;
        if (!enabled || version == null || version.isEmpty()) {
            final File d = punctDir(c);
            if (d.exists()) {
                deleteTree(d);
                Log.i(TAG, "offline: 标点模型缓存已清");
                report(c);
            }
            return;
        }
        if (version.equals(cachedVersionIn(punctDir(c)))) {
            Log.i(TAG, "offline: 标点模型已是最新 " + version);
            return;
        }
        new Thread(() -> {
            try {
                pullInto(c, "punct", punctDir(c), version);
                report(c);
            } catch (Throwable tr) {
                Log.w(TAG, "offline: 标点模型同步失败: " + tr);
            }
        }, "bzk-offline-punct").start();
    }

    /** 某个目录里的版本号（与 {@link #cachedVersion} 同逻辑，但作用在任意目录）。 */
    private static String cachedVersionIn(File dir) {
        final File f = new File(dir, VERSION_FILE);
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            final byte[] b = new byte[(int) Math.min(128, f.length())];
            final int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable tr) {
            return "";
        }
    }

    /** 经 provider 把模型文件拷进本地目录。 */
    private static void pull(Context c, String id, String version) throws Exception {
        pullInto(c, id, dirOf(c, id), version);
    }

    /** 通用版：把 {@code modelId} 的清单文件拷进 {@code dir}（ASR 模型与标点模型共用）。 */
    private static void pullInto(Context c, String id, File dir, String version) throws Exception {
        final ContentResolver cr = c.getContentResolver();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("建目录失败 " + dir);
        final long t0 = android.os.SystemClock.uptimeMillis();
        long total = 0;
        int files = 0;
        try (Cursor cur = cr.query(Uri.parse("content://" + AUTHORITY + "/" + id),
                null, null, null, null)) {
            if (cur == null || cur.getCount() == 0) {
                throw new Exception("provider 没给出文件清单（App 侧还没下载完？）");
            }
            while (cur.moveToNext()) {
                final String name = cur.getString(0);
                final long bytes = cur.getLong(1);
                final String sha = cur.getString(2);
                final File part = new File(dir, name + ".part");
                final File fin = new File(dir, name);
                if (fin.isFile() && fin.length() == bytes) {
                    total += bytes;
                    files++;
                    continue;
                }
                try (InputStream in = cr.openInputStream(
                        Uri.parse("content://" + AUTHORITY + "/" + id + "/" + name));
                     FileOutputStream os = new FileOutputStream(part)) {
                    if (in == null) throw new Exception("openInputStream 返回 null: " + name);
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (part.length() != bytes && bytes > 0) {
                    throw new Exception(name + " 体积不符：" + part.length() + " != " + bytes);
                }
                if (sha != null && !sha.isEmpty()) {
                    final String got = sha256(part);
                    if (!sha.equalsIgnoreCase(got)) throw new Exception(name + " 哈希不符");
                }
                //noinspection ResultOfMethodCallIgnored
                fin.delete();
                if (!part.renameTo(fin)) throw new Exception("改名失败 " + name);
                total += bytes;
                files++;
                Log.i(TAG, "offline: 拷贝 " + name + " 完成（" + bytes + "B）");
            }
        }
        try (FileOutputStream os = new FileOutputStream(new File(dir, VERSION_FILE))) {
            os.write((version == null ? "" : version).getBytes("UTF-8"));
        }
        Log.i(TAG, "offline: 同步完成 " + id + " files=" + files + " bytes=" + total
                + " 用时 " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String sha256(File f) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
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

    /** 把"输入法侧的缓存状态"回传给 App（主页状态行显示"已同步/待同步"）。 */
    private static void report(Context c) {
        final String info = info(c);
        sLastInfo = info;
        BroadcastConfig.sendOfflineState(c, info);
    }
}
