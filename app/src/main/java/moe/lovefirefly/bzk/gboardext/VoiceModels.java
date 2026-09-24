package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线语音的**模型清单 + 本地状态**（见 local/plan.md §19、§22）。
 *
 * <p><b>为什么权重不进 APK</b>：228MB 的模型打进 APK 不现实（APK 会变成 250MB+，每次更新都重下），
 * 所以模型是**下载**的：App 侧前台服务下载到自己的 files 目录（母本），再由注入侧经
 * ContentProvider 拉进 Gboard 自己的存储 —— 实测 Gboard **读不了**别的 App 的文件（EACCES），
 * 只能走 provider 交 FD。
 *
 * <p><b>两个尺寸</b>（用户口径："我们提供 2 个尺寸"）：
 * <ul>
 *   <li>{@code sensevoice}：SenseVoice-Small int8，228MB 档，准确率较好、支持中/粤/英/日/韩；</li>
 *   <li>{@code paraformer}：Paraformer-zh-small int8，78MB 档，准确率稍次、中英双语。</li>
 * </ul>
 * 体积是**逐文件字节数的和**（不是压缩包），界面上按 MB(10^6) 显示。
 *
 * <p><b>状态</b>：未下载（空）/ 下载中（{@link #STATE_DOWNLOADING}）/ 已就绪（{@link #STATE_READY}）。
 * 只有"已就绪"才允许勾选（用户口径："如果没有下载，则无法被选择"）。勾选与上面的
 * 「选择配置文件」**互斥**（同一时刻只有一个引擎生效）。
 */
final class VoiceModels {

    private static final String TAG = "GboardExt";

    private static final String K_SELECTED = "offlineModel";
    private static final String K_STATE = "offlineModelState.";
    private static final String K_PROGRESS = "offlineModelProgress.";

    static final String STATE_READY = "ready";
    static final String STATE_DOWNLOADING = "downloading";

    private static final String HF = "https://hf-mirror.com/csukuangfj/";
    private static final String HF_ALT = "https://huggingface.co/csukuangfj/";

    /** 一个要下载的文件（多个来源按顺序试）。 */
    static final class FileSpec {
        final String name;
        final long bytes;
        final String sha256;              // 空 = 暂不校验哈希（只看体积）
        final String[] urls;

        FileSpec(String name, long bytes, String sha256, String... urls) {
            this.name = name;
            this.bytes = bytes;
            this.sha256 = sha256 == null ? "" : sha256;
            this.urls = urls;
        }
    }

    /** 一个可下载的模型。 */
    static final class Model {
        final String id;
        final String label;
        final String note;                // 准确率描述
        final String dir;                 // files/models/<dir>/
        final String engineId;            // 勾选后生效的引擎 id（离线引擎脚本）
        final List<FileSpec> files = new ArrayList<>();

        Model(String id, String label, String note, String dir, String engineId) {
            this.id = id;
            this.label = label;
            this.note = note;
            this.dir = dir;
            this.engineId = engineId;
        }

        long totalBytes() {
            long n = 0;
            for (FileSpec f : files) n += f.bytes;
            return n;
        }

        /** 界面上显示的体积：按 MB(10^6) 取整（"约 240 MB"）。 */
        String sizeText() {
            return "约 " + Math.round(totalBytes() / 1_000_000.0) + " MB";
        }
    }

    private static List<Model> sCatalog;

    /** 清单（顺序 = 界面顺序）。体积/哈希来自 2026-09-24 实测（local/models/sha256.txt）。 */
    static synchronized List<Model> all() {
        if (sCatalog != null) return sCatalog;
        final List<Model> out = new ArrayList<>();

        // 准确率口径：AISHELL-1 测试集**去标点纯字错率**（官方论文/官方 CER 表），
        // 不是本 int8 文件实测，也不是"带标点的正确率"——界面上有说明句兜底。
        final Model sv = new Model("sensevoice", "SenseVoice-Small",
                "准确率较好（约 97%）", "sensevoice", "builtin-sensevoice");
        final String svRepo = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";
        sv.files.add(new FileSpec("model.int8.onnx", 239233841L,
                "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                HF + svRepo + "model.int8.onnx", HF_ALT + svRepo + "model.int8.onnx"));
        sv.files.add(new FileSpec("tokens.txt", 315894L,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                HF + svRepo + "tokens.txt", HF_ALT + svRepo + "tokens.txt"));
        out.add(sv);

        final Model pf = new Model("paraformer", "Paraformer-Small",
                "准确率稍次（约 96%）", "paraformer", "builtin-paraformer");
        final String pfRepo = "sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/";
        pf.files.add(new FileSpec("model.int8.onnx", 81828675L,
                "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
                HF + pfRepo + "model.int8.onnx", HF_ALT + pfRepo + "model.int8.onnx"));
        pf.files.add(new FileSpec("tokens.txt", 75352L,
                "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
                HF + pfRepo + "tokens.txt", HF_ALT + pfRepo + "tokens.txt"));
        out.add(pf);

        sCatalog = out;
        return out;
    }

    static Model find(String id) {
        for (Model m : all()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(GboardConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static File dirOf(Context c, Model m) {
        return new File(c.getFilesDir(), "models/" + m.dir);
    }

    // ------------------------------------------------------------------ 状态

    static String state(Context c, String id) {
        return prefs(c).getString(K_STATE + id, "");
    }

    static void setState(Context c, String id, String st) {
        prefs(c).edit().putString(K_STATE + id, st == null ? "" : st).apply();
    }

    /** 下载进度 0..100（-1 = 没有在下载）。 */
    static int progress(Context c, String id) {
        return prefs(c).getInt(K_PROGRESS + id, -1);
    }

    static void setProgress(Context c, String id, int p) {
        prefs(c).edit().putInt(K_PROGRESS + id, p).apply();
    }

    /**
     * 是否"可以用"：状态是 ready <b>且**文件真的在、体积对得上**（防止手动清了 files 目录、
     * 或上次下载中途被杀留下的半份文件）。
     */
    static boolean ready(Context c, Model m) {
        if (!STATE_READY.equals(state(c, m.id))) return false;
        for (FileSpec f : m.files) {
            final File fp = new File(dirOf(c, m), f.name);
            if (!fp.isFile() || fp.length() != f.bytes) {
                Log.w(TAG, "model " + m.id + " 文件不齐: " + f.name
                        + " len=" + (fp.isFile() ? fp.length() : -1) + " 期望 " + f.bytes);
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 选择（与配置文件互斥）

    static String selected(Context c) {
        return prefs(c).getString(K_SELECTED, "");
    }

    /** 勾选/取消某个离线模型。勾上时**清掉所有配置文件的勾选**（互斥）。 */
    static void setSelected(Context c, String id, boolean on) {
        prefs(c).edit().putString(K_SELECTED, on && id != null ? id : "").apply();
        if (on) VoiceProfiles.clearEnabled(c);        // 互斥：上面的配置文件全部取消
        Log.i(TAG, "offline model selected=" + (on ? id : "(none)"));
    }

    /** 供 {@link VoiceProfiles#setEnabled} 调用：勾了配置文件就把离线模型取消（互斥）。 */
    static void clearSelection(Context c) {
        if (selected(c).isEmpty()) return;
        prefs(c).edit().putString(K_SELECTED, "").apply();
        Log.i(TAG, "offline model deselected (profile enabled)");
    }

    // ------------------------------------------------------------------ 删除

    /** 删除本地模型（文件 + 状态 + 进度）。 */
    static void delete(Context c, Model m) {
        final File dir = dirOf(c, m);
        final File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        setState(c, m.id, "");
        setProgress(c, m.id, -1);
        if (m.id.equals(selected(c))) clearSelection(c);
        Log.i(TAG, "model deleted: " + m.id);
    }

    /** 所有已就绪模型的总体积（界面/说明用）。 */
    static long totalReadyBytes(Context c) {
        long n = 0;
        for (Model m : all()) {
            if (ready(c, m)) n += m.totalBytes();
        }
        return n;
    }
}
