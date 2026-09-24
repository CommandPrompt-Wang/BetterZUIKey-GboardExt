package moe.lovefirefly.bzk.gboardext;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * 把**离线模型文件**交给 Gboard 进程读取（见 local/plan.md §23）。
 *
 * <p><b>为什么必须走 provider</b>：实测 Gboard **读不了**我们 App 的文件 ——
 * 模块的 {@code /sdcard/Android/media/…} 它看得见（exists/len 都对）但读是 {@code EACCES}、
 * 写 {@code EPERM}；{@code Android/data}、{@code /data/local/tmp} 更不行。
 * ContentProvider 是系统机制（SAF 就这么工作）：provider 进程自己打开文件、把 **FD** 递过去，
 * 读检查发生在 provider 侧，客户端拿 FD 读内容不再受路径权限限制。
 * 模块里已有先例：{@link ConfigProvider}（Gboard 侧用 ContentResolver 读配置）。
 *
 * <p><b>URI 形态</b>：
 * <pre>
 *   content://moe.lovefirefly.bzk.gboardext.model/&lt;模型id&gt;          ← query：返回该模型的
 *                                                                      文件清单（name/bytes/sha256）
 *   content://moe.lovefirefly.bzk.gboardext.model/&lt;模型id&gt;/&lt;文件名&gt;  ← openFile：交 FD
 * </pre>
 *
 * <p><b>安全</b>：同 {@link ConfigProvider} —— 只放行"自己"和 Gboard 的 uid；
 * 文件名必须在清单里（不让外面拼任意路径读 App 私有目录里的别的东西）。
 */
public class ModelProvider extends ContentProvider {

    private static final String TAG = "GboardExt";
    private static final String SELF_PKG = "moe.lovefirefly.bzk.gboardext";
    private static final String GB_PKG = "com.google.android.inputmethod.latin";

    public static final String AUTHORITY = "moe.lovefirefly.bzk.gboardext.model";

    /** 模型根 URI（query 用）。 */
    static Uri uriFor(String modelId) {
        return Uri.parse("content://" + AUTHORITY + "/" + modelId);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        final String[] cols = {"name", "bytes", "sha256"};
        final MatrixCursor c = new MatrixCursor(cols);
        if (!isCallerAllowed()) {
            android.util.Log.w(TAG, "model provider: reject uid=" + Binder.getCallingUid());
            return c;
        }
        final String id = uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0);
        final VoiceModels.Model m = VoiceModels.find(id);
        if (m == null || getContext() == null) return c;
        if (!VoiceModels.ready(getContext(), m)) {
            android.util.Log.w(TAG, "model provider: " + id + " 还没下载完");
            return c;
        }
        for (VoiceModels.FileSpec f : m.files) {
            c.addRow(new Object[]{f.name, f.bytes, f.sha256});
        }
        return c;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws java.io.FileNotFoundException {
        if (!isCallerAllowed()) {
            throw new java.io.FileNotFoundException("caller not allowed: "
                    + Binder.getCallingUid());
        }
        final java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() < 2 || getContext() == null) {
            throw new java.io.FileNotFoundException("bad uri " + uri);
        }
        final String id = seg.get(0);
        final String name = seg.get(1);
        final VoiceModels.Model m = VoiceModels.find(id);
        if (m == null) throw new java.io.FileNotFoundException("unknown model " + id);
        boolean known = false;
        for (VoiceModels.FileSpec f : m.files) {
            if (f.name.equals(name)) known = true;
        }
        if (!known) throw new java.io.FileNotFoundException("not in manifest: " + name);
        final File fp = new File(VoiceModels.dirOf(getContext(), m), name);
        if (!fp.isFile()) throw new java.io.FileNotFoundException("missing " + fp);
        return ParcelFileDescriptor.open(fp, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private boolean isCallerAllowed() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        final android.content.Context ctx = getContext();
        if (ctx == null) return false;
        final String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) return false;
        for (String p : pkgs) {
            if (SELF_PKG.equals(p) || GB_PKG.equals(p)) return true;
        }
        return false;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "application/octet-stream";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;                       // 只读
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] args) {
        return 0;                          // 只读
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;                          // 只读
    }
}
