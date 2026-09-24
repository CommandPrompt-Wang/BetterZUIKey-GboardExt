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
 * <p><b>两个尺寸</b>（用户口径："我们提供 2 个尺寸"）+ 两个**流式**档位：
 * <ul>
 *   <li>{@code sensevoice}：SenseVoice-Small int8，228MB 档，准确率较好、支持中/粤/英/日/韩；</li>
 *   <li>{@code paraformer}：Paraformer-zh-small int8，78MB 档，准确率稍次、中英双语。</li>
 *   <li>{@code zipformer-zh} / {@code zipformer-bi}：流式 transducer（24MB / 189MB），**边说边出字**，
 *       不带标点（要开「补全标点」）。</li>
 * </ul>
 * 体积是**逐文件字节数的和**（不是压缩包），界面上按 XiB 显示（用户口径：统一 XiB）。
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
    /** 「启用切分」开关（共享的 silero_vad）。 */
    private static final String K_VAD = "offlineVad";
    /** 「补全标点」开关（中英离线标点模型）。 */
    private static final String K_PUNCT = "offlinePunct";

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
        /** 准确率文案，如 {@code "97%"}；**空串 = 暂无数据**（官方没公布过这批权重）。 */
        final String accuracy;
        /** 语言：{@code "中英"} / {@code "仅中文"}。 */
        final String languages;
        /** 是否**自带**标点（不带的话要靠「补全标点」）。 */
        final boolean hasPunctuation;
        final String dir;                 // files/models/<dir>/
        final String engineId;            // 勾选后生效的引擎 id（离线引擎脚本）
        /**
         * 流式模型（transducer：encoder/decoder/joiner 三件套）——**边说边出字**，
         * 所以「启用切分」（silero_vad 模拟流式）对它没有意义（见 {@code zipformer-*.js}）。
         */
        final boolean streaming;
        final List<FileSpec> files = new ArrayList<>();

        Model(String id, String label, String dir, String engineId,
                String accuracy, String languages, boolean hasPunctuation) {
            this(id, label, dir, engineId, accuracy, languages, hasPunctuation, false);
        }

        Model(String id, String label, String dir, String engineId,
                String accuracy, String languages, boolean hasPunctuation, boolean streaming) {
            this.id = id;
            this.label = label;
            this.dir = dir;
            this.engineId = engineId;
            this.accuracy = accuracy == null ? "" : accuracy;
            this.languages = languages == null ? "" : languages;
            this.hasPunctuation = hasPunctuation;
            this.streaming = streaming;
        }

        long totalBytes() {
            long n = 0;
            for (FileSpec f : files) n += f.bytes;
            return n;
        }

        /** 界面上显示的体积：**统一 XiB**（KiB/MiB/GiB，用户口径：不要 MB/GB 混用）。 */
        String sizeText() {
            final long n = totalBytes();
            if (n < 1024L * 1024) return "约 " + Math.round(n / 1024.0) + " KiB";
            if (n < 1024L * 1024 * 1024) return "约 " + Math.round(n / 1048576.0) + " MiB";
            return String.format(java.util.Locale.ROOT, "约 %.1f GiB", n / 1073741824.0);
        }

        /**
         * 卡片第二行（用户口径，逐字照排，分隔符是 {@code ·} 不含空格）：
         * <pre>
         *   流式模型·约 24 MiB·准确率暂无数据·仅中文·无标点
         *              约 228 MiB·准确率97%·中英·含标点
         * </pre>
         * 「流式模型」只在流式档位前面有。
         */
        String infoText() {
            return (streaming ? "流式模型·" : "")
                    + sizeText()
                    + "·准确率" + (accuracy.isEmpty() ? "暂无数据" : accuracy)
                    + "·" + languages
                    + "·" + (hasPunctuation ? "含标点" : "无标点");
        }
    }

    private static List<Model> sCatalog;

    /** 清单（顺序 = 界面顺序）。体积/哈希来自 2026-09-24 实测（`local/models/sha256.txt`
     * 与流式档位的 `local/models/sha256_stream.txt`）。 */
    static synchronized List<Model> all() {
        if (sCatalog != null) return sCatalog;
        final List<Model> out = new ArrayList<>();

        // 准确率口径：AISHELL-1 测试集**去标点纯字错率**（官方论文/官方 CER 表），
        // 不是本 int8 文件实测，也不是"带标点的正确率"——界面上有说明句兜底。
        final Model sv = new Model("sensevoice", "SenseVoice-Small", "sensevoice",
                "builtin-sensevoice", "97%", "中英", true);
        final String svRepo = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";
        sv.files.add(new FileSpec("model.int8.onnx", 239233841L,
                "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                HF + svRepo + "model.int8.onnx", HF_ALT + svRepo + "model.int8.onnx"));
        sv.files.add(new FileSpec("tokens.txt", 315894L,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                HF + svRepo + "tokens.txt", HF_ALT + svRepo + "tokens.txt"));
        out.add(sv);

        final Model pf = new Model("paraformer", "Paraformer-Small", "paraformer",
                "builtin-paraformer", "96%", "中英", false);
        final String pfRepo = "sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/";
        pf.files.add(new FileSpec("model.int8.onnx", 81828675L,
                "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
                HF + pfRepo + "model.int8.onnx", HF_ALT + pfRepo + "model.int8.onnx"));
        pf.files.add(new FileSpec("tokens.txt", 75352L,
                "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
                HF + pfRepo + "tokens.txt", HF_ALT + pfRepo + "tokens.txt"));
        out.add(pf);

        // ---- 流式档位（Zipformer transducer，边说边出字）----
        // 与非流式两档的区别：这两档**自己就是流式**，录音期间就有部分结果（不需要 VAD 切分）；
        // 代价是它们都**不带标点**（需要上面的「补全标点」），且官方没有公开这两份权重的 CER，
        // 所以界面上不给百分比（那个百分比只对 AISHELL-1 上公开过 CER 的两档有效）。
        final Model zz = new Model("zipformer-zh", "Zipformer-中文", "zipformer-zh",
                "builtin-zipformer-zh", "", "仅中文", false, true);
        final String zzRepo = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/";
        zipformer(zz, zzRepo, 21621684L,
                "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
                1888682L, "22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf",
                1795562L, "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
                48697L, "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac");
        out.add(zz);

        final Model zb = new Model("zipformer-bi", "Zipformer-中英", "zipformer-bi",
                "builtin-zipformer-bi", "", "中英", false, true);
        final String zbRepo =
                "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/";
        zipformer(zb, zbRepo, 181895032L,
                "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
                13091040L, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa",
                3228404L, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
                56317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3");
        out.add(zb);

        sCatalog = out;
        return out;
    }

    /**
     * 流式 zipformer 的四个文件（encoder/decoder/joiner + tokens）。
     *
     * <p>体积/哈希来自 2026-09-24 实测（{@code local/models/sha256_stream.txt}）。
     */
    private static void zipformer(Model m, String repo, long encBytes, String encSha,
            long decBytes, String decSha, long joinBytes, String joinSha,
            long tokBytes, String tokSha) {
        m.files.add(new FileSpec("encoder-epoch-99-avg-1.int8.onnx", encBytes, encSha,
                HF + repo + "encoder-epoch-99-avg-1.int8.onnx",
                HF_ALT + repo + "encoder-epoch-99-avg-1.int8.onnx"));
        m.files.add(new FileSpec("decoder-epoch-99-avg-1.int8.onnx", decBytes, decSha,
                HF + repo + "decoder-epoch-99-avg-1.int8.onnx",
                HF_ALT + repo + "decoder-epoch-99-avg-1.int8.onnx"));
        m.files.add(new FileSpec("joiner-epoch-99-avg-1.int8.onnx", joinBytes, joinSha,
                HF + repo + "joiner-epoch-99-avg-1.int8.onnx",
                HF_ALT + repo + "joiner-epoch-99-avg-1.int8.onnx"));
        m.files.add(new FileSpec("tokens.txt", tokBytes, tokSha,
                HF + repo + "tokens.txt", HF_ALT + repo + "tokens.txt"));
    }

    /**
     * 版本号：给 Gboard 判断"我这份缓存要不要重拉"用。
     *
     * <p>取"模型 id + **清单里每个文件**的 sha256 前 8 位（没哈希就用体积）+ 总字节数"。
     * 早先只取第一个 {@code .onnx} 的哈希 —— 多文件模型（流式 zipformer 是
     * encoder/decoder/joiner/tokens 四件套）只换了 decoder 的话版本号不变，
     * 宿主会以为"已是最新"而继续用半新半旧的缓存。
     */
    static String versionOf(Model m) {
        if (m == null) return "";
        final StringBuilder sb = new StringBuilder(m.id);
        for (FileSpec f : m.files) {
            sb.append('.').append(f.sha256.isEmpty()
                    ? Long.toHexString(f.bytes)
                    : f.sha256.substring(0, 8));
        }
        return sb.append('.').append(m.totalBytes()).toString();
    }

    /**
     * **补全标点模型**（中英，CT-Transformer）：给不带标点的模型（Paraformer / 流式模型）用。
     *
     * <p>体积/哈希是 2026-09-24 实下校验的；官方说只需要 {@code model.int8.onnx} 这一个文件。
     * 它和 ASR 模型一样走"下载 → provider 交付 → 宿主缓存"，但**是附加共享件**：
     * 宿主那边的"只留当前 ASR 模型"清扫不能把它删掉（见 {@code OfflineModels.sync}）。
     */
    private static volatile Model sPunct;

    static Model punct() {
        if (sPunct == null) {
            final Model m = new Model("punct", "标点模型（中英）", "punct", "", "", "", true);
            m.files.add(new FileSpec("model.int8.onnx", 75519198L,
                    "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"
                            + "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/"
                            + "model.int8.onnx"));
            sPunct = m;
        }
        return sPunct;
    }

    /** 「补全标点」开关。 */
    static boolean punctEnabled(Context c) {
        return prefs(c).getBoolean(K_PUNCT, false);
    }

    static void setPunctEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_PUNCT, on).apply();
    }

    static String versionOfPunct() {
        return versionOf(punct());
    }

    /** 「启用切分」开关（VAD；只在非流式模型下有意义）。 */
    static boolean vadEnabled(Context c) {
        return prefs(c).getBoolean(K_VAD, false);
    }

    static void setVadEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_VAD, on).apply();
    }

    /**
     * 按 id 找模型：**ASR 模型与附加共享件（标点）都要找**。
     *
     * <p>踩过：provider / 下载服务原来只调 {@link #find}（只查 ASR 目录），于是标点模型
     * "明明文件在、状态也 ready"，provider 却报"清单为空"、下载也找不到目标。
     */
    static Model byId(String id) {
        final Model m = find(id);
        if (m != null) return m;
        final Model punct = punct();
        return punct.id.equals(id) ? punct : null;
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
