package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;

/**
 * 离线识别（sherpa-onnx）——**跑在 Gboard 进程里**，给引擎脚本用的宿主原语后端。
 *
 * <p>对应脚本里的 {@code ctx.localAsr("sensevoice", frames)}（见 {@link ScriptEngine}）。
 * 模型文件在 **Gboard 自己的缓存目录** {@code files/bzk-models/<id>/}（由 {@link OfflineModels}
 * 从 App 经 provider 拷过来，见 local/plan.md §23/§25）。
 *
 * <p><b>两种模型</b>：{@code sensevoice}（SenseVoice-Small int8，中/粤/英/日/韩 + ITN 带标点）与
 * {@code paraformer}（Paraformer-zh-small int8，中英，无标点）。
 *
 * <p><b>非流式</b>：这两档只有整段解码，所以录音期间没有部分结果 —— 脚本把帧缓存到
 * {@code engine.stop} 时一次解码；或者开「启用切分」用 silero_vad 切句（模拟流式）。
 *
 * <p><b>流式</b>：{@code zipformer-zh} / {@code zipformer-bi}（transducer 三件套）走
 * {@link #startOnline} / {@link #feedOnline} / {@link #finishOnline} —— 真的边说边出字，
 * 那一档不需要（也不看）VAD 开关。
 *
 * <p><b>内存</b>：模型加载后要占几百 MB（实测机型可用内存本就紧张），所以空闲
 * {@link #IDLE_RELEASE_MS} 后释放 recognizer；线程数也压到 {@link #THREADS}（§19.2 的结论）。
 */
final class LocalAsr {

    private static final String TAG = "GboardExt";

    private static final int SAMPLE_RATE = 16000;
    private static final int THREADS = 6;
    /** 空闲多久释放 recognizer（内存换时间）。大档位吃内存，留短一点。 */
    private static final long IDLE_RELEASE_MS = 60_000;
    /** 小档位（zipformer-zh 24MiB / paraformer 78MiB）多留一会儿：加载要 2~7 秒，
     *  而它们占的内存只有几十 MB —— 让"停一会儿再说一句"不必重新加载。 */
    private static final long IDLE_RELEASE_SMALL_MS = 10 * 60_000;
    /** 大档位（SenseVoice 228MiB / zipformer-bi 189MiB）：留 3 分钟，再久就还给系统。 */
    private static final long IDLE_RELEASE_BIG_MS = 3 * 60_000;
    /** 低于这个字节数（0.1s）不值得解码。 */
    private static final int MIN_PCM = 3200;

    /** VAD 模型在 APK assets 里的路径 + 抽出来后的位置（与模型缓存分开，见 §23 的"只留当前模型"）。 */
    private static final String VAD_ASSET = "models/silero_vad.onnx";
    private static final long VAD_BYTES = 643854L;
    private static final int VAD_WINDOW = 512;              // silero 的窗口（32ms @16k）
    private static XposedModule sModule;                    // 抽 asset 用（install 时给）

    private static final Object LOCK = new Object();
    private static OfflineRecognizer sRec;
    private static Vad sVad;
    private static final StringBuilder sStreamText = new StringBuilder();
    private static float[] sWin = new float[VAD_WINDOW];
    private static int sWinN;
    private static volatile boolean sStreamOn;

    // ---- 流式档位（Zipformer transducer：encoder/decoder/joiner + tokens）----
    /** 流式模型的文件名（sherpa 官方导出的就是这几个名字，两档一致）。 */
    private static final String[] ONLINE_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
    };
    /**
     * 流式解码线程数：比整段解码少一点 —— 它每 40ms 就来一次增量解码，线程开满只会
     * 抢内存/调度（流式权重本身就是按"低延迟小步"设计的）。
     */
    private static final int ONLINE_THREADS = 4;
    /** volatile：引擎线程只读它们（**不能**去抢 LOCK —— 预热线程正拿着 LOCK 加载模型）。 */
    private static volatile OnlineRecognizer sOnline;
    private static volatile OnlineStream sOnlineStream;
    private static volatile String sOnlineModel = "";
    private static volatile String sOnlineLoading = "";
    private static volatile String sOfflineLoading = "";
    /** 上一次记过日志的流式文本（只在变化时记一行，见 {@link #feedOnline}）。 */
    private static String sOnlineLast = "";
    /** 上一次的最终文本（标点后）：文本没变时直接复用，不再空跑标点模型。 */
    private static String sOnlineLastText = "";
    /**
     * **预滚缓冲**：模型还没加载完时收到的音频先攒着（有上限），等模型就绪再一起补喂。
     *
     * <p>为什么需要：原来模型没就绪就把音频直接丢掉 —— 用户按下语音键马上说话，
     * 那句开头（冷启动时要 2~7.5 秒）**整句消失**，看起来像"识别必须从完整句子开始"。
     * 上限 15 秒（375 帧 × 1280B ≈ 470KB）足够盖住一次加载，且不会像无界队列那样积压。
     */
    private static final int PRE_ROLL_MAX_FRAMES = 375;
    private static final java.util.ArrayDeque<byte[]> sPreRoll = new java.util.ArrayDeque<>();

    // ---- 「补全标点」（给不带标点的模型：Paraformer / 流式模型）----
    private static volatile boolean sPunctOn;
    private static OfflinePunctuation sPunct;
    private static final String PUNCT_DIR = "bzk-models/punct";
    private static final String PUNCT_FILE = "model.int8.onnx";
    private static String sRecModel = "";
    private static long sLastUse;

    private LocalAsr() {
    }

    /** PCM16 / 16k / mono → 文本。失败抛异常（脚本侧会把它变成"出现错误：…"上屏）。 */
    static String decode(Context ctx, String modelId, byte[] pcm) throws Exception {
        if (pcm == null || pcm.length < MIN_PCM) {
            throw new Exception("音频太短（" + (pcm == null ? 0 : pcm.length) + " 字节）");
        }
        synchronized (LOCK) {
            final OfflineRecognizer rec = recognizer(ctx, modelId);
            String text = decodeSamples(ctx, modelId, toFloats(pcm));
            text = punctuate(ctx, text);
            Log.i(TAG, "offline-asr: model=" + modelId + " pcm=" + pcm.length + "B -> \""
                    + text + "\"");
            return text;
        }
    }

    /**
     * **预热**：会话一开始就异步把 recognizer 加载好。
     *
     * <p>为什么必须预热：模型加载要 3 秒左右，而 Gboard 的语音会话在用户按"停"那一刻就结束了 ——
     * 如果等 stop 才加载，结果会比会话晚 6 秒（加载 3s + 解码 3s）才发出去，Gboard 直接不认
     * （实测：日志里 {@code sink emit} 明明发了，输入框里什么都没有）。预热把加载挪到"用户还在说话"
     * 的那几秒里，stop 之后只剩解码。
     */
    static void preload(final Context ctx, final String modelId) {
        if (ctx == null || modelId == null || modelId.isEmpty()) return;
        // 同一档位同时只允许一个加载线程：踩过 —— 预热线程与引擎线程（脚本 start 里的
        // localAsrStreamStart）各加载了一份 189MiB 的模型，日志里两条"就绪"、内存翻倍、
        // 引擎线程还被卡了 7.5 秒（音频帧越堆越多 ⇒ 停止后 23 秒才出结果）。
        synchronized (LOCK) {
            if (isStreaming(modelId)) {
                if (modelId.equals(sOnlineModel) || modelId.equals(sOnlineLoading)) return;
                sOnlineLoading = modelId;
            } else {
                if (modelId.equals(sRecModel) || modelId.equals(sOfflineLoading)) return;
                sOfflineLoading = modelId;
            }
        }
        final boolean streaming = isStreaming(modelId);
        final Thread t = new Thread(() -> {
            try {
                if (streaming) onlineRecognizer(ctx, modelId);
                else recognizer(ctx, modelId);
                // 标点模型也一起预热：它 72MiB，原来是在**第一条文本**出现时同步加载的，
                // 会把引擎线程堵约 1 秒（实测日志里"标点模型就绪"正好卡在第一条 partial 前）。
                if (sPunctOn) punctModel(ctx);
            } catch (Throwable tr) {
                Log.w(TAG, "offline-asr: 预热失败: " + tr);
            } finally {
                synchronized (LOCK) {
                    if (streaming) sOnlineLoading = "";
                    else sOfflineLoading = "";
                }
            }
        }, "bzk-offline-preload");
        t.setDaemon(true);
        t.start();
    }

    static void setModule(XposedModule m) {
        sModule = m;
    }

    /** 「补全标点」开关（广播推来的状态）。 */
    static void setPunctEnabled(boolean on) {
        sPunctOn = on;
    }

    /**
     * 给文本补标点。开关没开、模型没下载、或出错时**原样返回**（不能因为标点失败影响识别）。
     *
     * <p>模型很快（官方示例 0.007–0.014 秒/句），所以每次部分结果更新都跑一遍 —— 体感就是
     * "边出字边带标点"。SenseVoice 自带标点，用户不开这个开关即可。
     */
    private static String punctuate(Context ctx, String text) {
        if (!sPunctOn || text == null || text.isEmpty() || ctx == null) return text;
        try {
            final OfflinePunctuation punct = punctModel(ctx);
            if (punct == null) return text;                   // 还没下载好，先原样
            return punct.addPunctuation(text);
        } catch (Throwable tr) {
            Log.w(TAG, "offline-asr: 补标点失败: " + tr);
            return text;
        }
    }

    /**
     * 取（必要时加载）标点模型。**别在第一条文本时才加载** —— 它 72MiB，同步加载会把引擎线程
     * 堵约 1 秒（实测第一条 partial 前面正好卡着一条"标点模型就绪"），所以预热时会先调这里。
     */
    private static OfflinePunctuation punctModel(Context ctx) throws Exception {
        if (sPunct != null) return sPunct;
        final File f = new File(ctx.getFilesDir(), PUNCT_DIR + "/" + PUNCT_FILE);
        if (!f.isFile()) return null;
        sPunct = new OfflinePunctuation(OfflinePunctuationConfig.builder()
                .setModel(OfflinePunctuationModelConfig.builder()
                        .setCtTransformer(f.getAbsolutePath())
                        .setNumThreads(1)
                        .build())
                .build());
        Log.i(TAG, "offline-asr: 标点模型就绪");
        return sPunct;
    }

    /** VAD 模型文件（抽到 {@code files/bzk-vad/}）。 */
    private static File vadFile(Context ctx) {
        final File f = new File(ctx.getFilesDir(), "bzk-vad/silero_vad.onnx");
        if (f.isFile() && f.length() == VAD_BYTES) return f;
        return NativeLibs.extractAsset(sModule, VAD_ASSET, f) ? f : null;
    }

    /** 开一条"模拟流式"会话：建 VAD（模型随 APK 打包，所以不需要下载/同步）。 */
    static synchronized void startStream(Context ctx, String modelId) {
        sStreamText.setLength(0);
        sWinN = 0;
        if (sWin.length != VAD_WINDOW) sWin = new float[VAD_WINDOW];
        sStreamOn = true;
        try {
            final File f = vadFile(ctx);
            if (f == null) {
                Log.w(TAG, "offline-asr: 取不到 VAD 模型，退回整段解码");
                sVad = null;
                return;
            }
            if (sVad == null) {
                sVad = new Vad(VadModelConfig.builder()
                        .setSileroVadModelConfig(SileroVadModelConfig.builder()
                                .setModel(f.getAbsolutePath())
                                .setThreshold(0.5f)
                                .setMinSilenceDuration(0.25f)   // 250ms 静音即判句尾（越小越实时）
                                .setMinSpeechDuration(0.2f)
                                .setWindowSize(VAD_WINDOW)
                                .setMaxSpeechDuration(6.0f)     // 长句最多 6 秒也切一段
                                .build())
                        .setSampleRate(SAMPLE_RATE)
                        .setNumThreads(1)
                        .build());
            } else {
                sVad.reset();
            }
            Log.i(TAG, "offline-asr: VAD 就绪（模拟流式：边切边解）");
        } catch (Throwable tr) {
            sVad = null;
            Log.w(TAG, "offline-asr: VAD 创建失败，退回整段解码: " + tr);
        }
    }

    /** 喂一帧音频；返回"到目前为止的全文"（没有新段完成就返回空串）。 */
    static synchronized String feed(Context ctx, String modelId, byte[] pcm) {
        if (!sStreamOn || sVad == null) return "";
        int off = 0;
        boolean changed = false;
        while (off + 1 < pcm.length) {
            sWin[sWinN++] = (short) ((pcm[off] & 0xff) | (pcm[off + 1] << 8)) / 32768.0f;
            off += 2;
            if (sWinN == VAD_WINDOW) {
                sWinN = 0;
                try {
                    sVad.acceptWaveform(sWin);
                } catch (Throwable tr) {
                    Log.w(TAG, "offline-asr: VAD 喂数据失败: " + tr);
                    sVad = null;
                    return "";
                }
                changed |= drain(ctx, modelId);
            }
        }
        return changed ? punctuate(ctx, sStreamText.toString()) : "";
    }

    /** 结束：flush 出最后一段并解码，返回全文。 */
    static synchronized String finishStream(Context ctx, String modelId) {
        if (!sStreamOn || sVad == null) {
            sStreamOn = false;
            return "";
        }
        try {
            sVad.flush();
            drain(ctx, modelId);
        } catch (Throwable tr) {
            Log.w(TAG, "offline-asr: VAD flush 失败: " + tr);
        }
        sStreamOn = false;
        return punctuate(ctx, sStreamText.toString());
    }

    // ------------------------------------------------------------ 流式（Zipformer）

    /**
     * 开一条**真流式**会话（{@code zipformer-zh} / {@code zipformer-bi}）。
     *
     * <p>与非流式那两档的区别：这里不攒帧、不用 VAD —— 每隔 40ms 喂进去就能拿到"到目前为止的
     * 全文"（{@link #feedOnline}），所以是真正的边说边出字。「启用切分」开关对这一档无效。
     *
     * <p><b>绝不在这里等模型加载</b>：加载 24MiB 档要 2.3 秒、189MiB 档要 7.5 秒 —— 引擎线程
     * 一等，音频帧就在队列里堆成几十秒的积压（实测停止后 23 秒才出结果、还和已上屏的文字重叠）。
     * 所以这里只负责"让预热线程去加载" + 清状态，stream 由 {@link #feedOnline} **懒建**。
     */
    static void startOnline(Context ctx, String modelId) {
        sOnlineLast = "";
        sOnlineLastText = "";
        synchronized (sPreRoll) {
            sPreRoll.clear();
        }
        releaseOnlineStream();
        if (sOnline != null && modelId.equals(sOnlineModel)) {
            sOnlineStream = sOnline.createStream();
            Log.i(TAG, "offline-asr: 流式会话开始 model=" + modelId + "（模型已就绪）");
            return;
        }
        preload(ctx, modelId);
        Log.i(TAG, "offline-asr: 流式会话开始 model=" + modelId
                + "（模型加载中：加载完之前的音频直接丢，不排队）");
    }

    /** 喂一帧 40ms 音频，返回"到目前为止的全文"（空串 = 模型还没就绪 / 文本没变）。
     *
     * <p>每帧都返回全文，脚本那边只在**变了**的时候才 {@code ctx.partial}（不变还发等于白刷
     * Gboard 的组合文本）。
     */
    static synchronized String feedOnline(Context ctx, String modelId, byte[] pcm) {
        if (sOnlineStream == null) {
            final OnlineRecognizer rec = sOnline;                 // volatile：别抢 LOCK
            if (rec == null || !modelId.equals(sOnlineModel)) {
                // 还在加载：先攒着（有上限，超了丢最旧的），别把用户说的第一句直接扔掉
                synchronized (sPreRoll) {
                    sPreRoll.addLast(pcm);
                    while (sPreRoll.size() > PRE_ROLL_MAX_FRAMES) sPreRoll.removeFirst();
                }
                return "";
            }
            sOnlineStream = rec.createStream();                   // 懒建（只在引擎线程里建）
            // 把加载期间攒下的音频**按顺序补喂**（用户说的第一句通常就在里面）
            final java.util.ArrayList<byte[]> pre;
            synchronized (sPreRoll) {
                pre = new java.util.ArrayList<>(sPreRoll);
                sPreRoll.clear();
            }
            if (!pre.isEmpty()) {
                Log.i(TAG, "offline-asr: 流式补喂预热期间的 " + pre.size() + " 帧（约 "
                        + (pre.size() * 40) + "ms 音频）");
                // 补喂按"只推进识别器"的方式做：不在这里递归调 feedOnline（会重复排队逻辑）
                for (byte[] f : pre) {
                    sOnlineStream.acceptWaveform(toFloats(f), SAMPLE_RATE);
                    while (sOnline.isReady(sOnlineStream)) sOnline.decode(sOnlineStream);
                }
            }
            Log.i(TAG, "offline-asr: 流式 stream 就绪（开始实时解码）");
        }
        try {
            sOnlineStream.acceptWaveform(toFloats(pcm), SAMPLE_RATE);
            // 增量解码：isReady() 才是"攒够一个 chunk 可以出结果了"（流式 transducer 的约定）
            while (sOnline.isReady(sOnlineStream)) {
                sOnline.decode(sOnlineStream);
            }
            final OnlineRecognizerResult r = sOnline.getResult(sOnlineStream);
            sLastUse = SystemClock.uptimeMillis();
            final String raw = r == null || r.getText() == null ? "" : r.getText().trim();
            // 文本没变就**别跑标点模型**：它每句 7–14ms，按 25 帧/秒空跑一遍等于白烧掉
            // 相当一部分 CPU（直接体现为启动更慢、实时余量更小）。
            if (raw.equals(sOnlineLast)) return sOnlineLastText;
            sOnlineLast = raw;
            final String text = punctuate(ctx, raw);
            sOnlineLastText = text;
            // 变化才记一行：排查"重复字/回退"时，这一行能区分
            // 「识别器本来就重复」/「标点模型改的」/「Gboard 那边合并出的」（后者日志是干净的）
            Log.i(TAG, "offline-asr: 流式 raw=\"" + raw + "\""
                    + (raw.equals(text) ? "" : " 补标点后=\"" + text + "\""));
            return text;
        } catch (Throwable tr) {
            Log.w(TAG, "offline-asr: 流式喂数据失败: " + tr);
            return "";
        }
    }

    /** 结束流式会话：flush 尾巴 + 取最终文本（并释放这条 stream）。 */
    static synchronized String finishOnline(Context ctx, String modelId) {
        if (sOnlineStream == null || !modelId.equals(sOnlineModel)) return "";
        String text = "";
        try {
            sOnlineStream.inputFinished();
            while (sOnline.isReady(sOnlineStream)) {
                sOnline.decode(sOnlineStream);
            }
            final OnlineRecognizerResult r = sOnline.getResult(sOnlineStream);
            text = r == null || r.getText() == null ? "" : r.getText().trim();
            sLastUse = SystemClock.uptimeMillis();
        } catch (Throwable tr) {
            Log.w(TAG, "offline-asr: 流式收尾失败: " + tr);
        } finally {
            try {
                sOnlineStream.release();
            } catch (Throwable ignored) {
            }
            sOnlineStream = null;
        }
        Log.i(TAG, "offline-asr: 流式结束 model=" + modelId + " -> \"" + text + "\"");
        return punctuate(ctx, text);
    }

    /** 模型 id 是不是流式档位（App 侧清单里的 {@code zipformer-*}）。 */
    private static boolean isStreaming(String modelId) {
        return modelId != null && modelId.startsWith("zipformer");
    }

    /** 还有没有加载着的模型（空闲释放线程据此决定要不要继续等，见 VoiceEngineHost）。 */
    static boolean hasLoaded() {
        return sRec != null || sOnline != null || sVad != null || sPunct != null;
    }

    /** 流式模型是否已经加载好（自检/日志用；引擎线程读的是 volatile，不会卡在加载上）。 */
    static boolean onlineReady(String modelId) {
        return sOnline != null && modelId != null && modelId.equals(sOnlineModel);
    }

    /** 建/取流式 recognizer（模型 24MB/189MB，加载慢 —— 一定要靠 {@link #preload}）。
     *
     * <p><b>整段加载都在 LOCK 里</b>：这样"预热线程"和任何别的调用者不会同时加载两份
     * （实测踩过：两条"就绪"日志、内存翻倍、加载时间也翻倍）。
     */
    private static OnlineRecognizer onlineRecognizer(Context ctx, String modelId) throws Exception {
        synchronized (LOCK) {
            if (sOnline != null && modelId.equals(sOnlineModel)) {
                sLastUse = SystemClock.uptimeMillis();
                return sOnline;
            }
            releaseOnlineLocked();
            final File dir = OfflineModels.dirOf(ctx, modelId);
            for (String n : ONLINE_FILES) {
                if (!new File(dir, n).isFile()) {
                    throw new Exception("流式模型不在本地（" + dir + "）——请先在设置页下载，"
                            + "并让输入法拉起一次以完成拷贝");
                }
            }
            LibraryLoader.setAutoLoadEnabled(false);
            final OnlineRecognizerConfig cfg = OnlineRecognizerConfig.builder()
                    .setOnlineModelConfig(OnlineModelConfig.builder()
                            .setTransducer(OnlineTransducerModelConfig.builder()
                                    .setEncoder(new File(dir, ONLINE_FILES[0]).getAbsolutePath())
                                    .setDecoder(new File(dir, ONLINE_FILES[1]).getAbsolutePath())
                                    .setJoiner(new File(dir, ONLINE_FILES[2]).getAbsolutePath())
                                    .build())
                            .setTokens(new File(dir, ONLINE_FILES[3]).getAbsolutePath())
                            .setNumThreads(ONLINE_THREADS)
                            .setDebug(false)
                            .setProvider("cpu")
                            .build())
                    // 我们从不调 reset() ⇒ 关掉端点检测，让它一路累积成"整段全文"
                    // （开了也不会自动重置，但内部记账没必要）
                    .setEnableEndpoint(false)
                    .setDecodingMethod("greedy_search")
                    .build();
            final long t0 = SystemClock.uptimeMillis();
            sOnline = new OnlineRecognizer(cfg);
            sOnlineModel = modelId;
            sLastUse = SystemClock.uptimeMillis();
            Log.i(TAG, "offline-asr: 流式 recognizer 就绪 model=" + modelId + " threads="
                    + ONLINE_THREADS + " 加载用时 " + (SystemClock.uptimeMillis() - t0) + "ms");
            return sOnline;
        }
    }

    /** 取出所有"已判完成"的语音段并逐段解码，累积到 {@link #sStreamText}。 */
    private static boolean drain(Context ctx, String modelId) {
        boolean changed = false;
        try {
            while (!sVad.empty()) {
                final SpeechSegment seg = sVad.front();
                sVad.pop();
                if (seg == null || seg.getSamples() == null
                        || seg.getSamples().length < SAMPLE_RATE / 10) {
                    continue;                                  // 不到 0.1 秒的碎片丢掉
                }
                final String t = decodeSamples(ctx, modelId, seg.getSamples());
                if (t != null && !t.isEmpty()) {
                    sStreamText.append(t);
                    changed = true;
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "offline-asr: 取语音段失败: " + tr);
        }
        return changed;
    }

    /** 空闲释放（内存约束）。由 {@link VoiceEngineHost} 在会话结束时顺带调一次。 */
    static void releaseIfIdle() {
        synchronized (LOCK) {
            final long idle = SystemClock.uptimeMillis() - sLastUse;
            final String cur = !sOnlineModel.isEmpty() ? sOnlineModel : sRecModel;
            if ((sRec != null || sOnline != null) && idle > idleWindow(cur)) {
                Log.i(TAG, "offline-asr: 空闲释放 recognizer（" + cur + "，留了 "
                        + (idleWindow(cur) / 1000) + "s）");
                releaseLocked();
            }
            if (sPunct != null && SystemClock.uptimeMillis() - sLastUse > IDLE_RELEASE_MS) {
                try {
                    sPunct.release();
                } catch (Throwable ignored) {
                }
                sPunct = null;
                Log.i(TAG, "offline-asr: 空闲释放标点模型");
            }
            if (sVad != null && !sStreamOn && SystemClock.uptimeMillis() - sLastUse > IDLE_RELEASE_MS) {
                try {
                    sVad.release();
                } catch (Throwable ignored) {
                }
                sVad = null;
                Log.i(TAG, "offline-asr: 空闲释放 VAD");
            }
        }
    }

    /**
     * 该档位的空闲释放窗口：大档位（SenseVoice / zipformer-bi）更吃内存，留 3 分钟；
     * 其余小档位留 10 分钟 —— 加载一次要 2~7 秒，频繁重加载比多占几十 MB 更影响体验。
     */
    private static long idleWindow(String modelId) {
        if (modelId == null) return IDLE_RELEASE_MS;
        if (modelId.contains("sensevoice") || modelId.contains("zipformer-bi")) {
            return IDLE_RELEASE_BIG_MS;
        }
        return IDLE_RELEASE_SMALL_MS;
    }

    /** 一段 float 采样 → 文本（VAD 分段与整段解码都走这里）。 */
    private static String decodeSamples(Context ctx, String modelId, float[] samples)
            throws Exception {
        final OfflineRecognizer rec = recognizer(ctx, modelId);
        final OfflineStream stream = rec.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            rec.decode(stream);
            final OfflineRecognizerResult r = rec.getResult(stream);
            sLastUse = SystemClock.uptimeMillis();
            return r == null || r.getText() == null ? "" : r.getText().trim();
        } finally {
            try {
                stream.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static OfflineRecognizer recognizer(Context ctx, String modelId) throws Exception {
        if (isStreaming(modelId)) {
            throw new Exception("这是流式模型，请走流式接口（" + modelId + "）");
        }
        if (sRec != null && modelId.equals(sRecModel)) {
            sLastUse = SystemClock.uptimeMillis();
            return sRec;
        }
        releaseLocked();
        final File dir = OfflineModels.dirOf(ctx, modelId);
        final File model = new File(dir, "model.int8.onnx");
        final File tokens = new File(dir, "tokens.txt");
        if (!model.isFile() || !tokens.isFile()) {
            throw new Exception("离线模型不在本地（" + dir + "）——请先在设置页下载，"
                    + "并让输入法拉起一次以完成拷贝");
        }
        // 库由 NativeLibs 用绝对路径 load 过（见 §24），别让它再按名字去找（Gboard 的 native
        // 搜索路径里没有我们的目录，必然 UnsatisfiedLinkError）
        LibraryLoader.setAutoLoadEnabled(false);

        final boolean paraformer = modelId.contains("paraformer");
        final OfflineModelConfig.Builder mb = OfflineModelConfig.builder()
                .setTokens(tokens.getAbsolutePath())
                .setNumThreads(THREADS)
                .setDebug(false)
                .setProvider("cpu");
        if (paraformer) {
            mb.setParaformer(OfflineParaformerModelConfig.builder()
                    .setModel(model.getAbsolutePath()).build());
        } else {
            mb.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                    .setModel(model.getAbsolutePath())
                    .setLanguage("auto")                  // 中/粤/英/日/韩 自动判
                    .setInverseTextNormalization(true)    // 数字/单位规整 + 标点
                    .build());
        }
        final OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(mb.build())
                .setDecodingMethod("greedy_search")
                .build();
        final long t0 = SystemClock.uptimeMillis();
        sRec = new OfflineRecognizer(cfg);
        sRecModel = modelId;
        sLastUse = SystemClock.uptimeMillis();
        Log.i(TAG, "offline-asr: recognizer 就绪 model=" + modelId + " threads=" + THREADS
                + " 加载用时 " + (SystemClock.uptimeMillis() - t0) + "ms");
        return sRec;
    }

    /** 释放两个 recognizer（切换/空闲时）。**流式正在用（stream 没释放）就别动它**。 */
    private static void releaseLocked() {
        if (sOnlineStream == null) releaseOnlineLocked();
        releaseOfflineLocked();
    }

    /** 只放掉流式的 stream（会话结束时用；recognizer 留着给下一句复用）。 */
    private static void releaseOnlineStream() {
        synchronized (LOCK) {
            if (sOnlineStream == null) return;
            try {
                sOnlineStream.release();
            } catch (Throwable ignored) {
            }
            sOnlineStream = null;
        }
    }

    /** 放掉流式 recognizer（连同它的 stream）。 */
    private static void releaseOnlineLocked() {
        if (sOnlineStream != null) {
            try {
                sOnlineStream.release();
            } catch (Throwable ignored) {
            }
            sOnlineStream = null;
        }
        if (sOnline == null) return;
        try {
            sOnline.release();
        } catch (Throwable ignored) {
        }
        sOnline = null;
        sOnlineModel = "";
    }

    /** 放掉非流式 recognizer。 */
    private static void releaseOfflineLocked() {
        if (sRec == null) return;
        try {
            sRec.release();
        } catch (Throwable ignored) {
        }
        sRec = null;
        sRecModel = "";
    }

    /** PCM16 → float[-1,1]（sherpa 要 float 采样）。 */
    private static float[] toFloats(byte[] pcm) {
        final int n = pcm.length / 2;
        final float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            final short s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            out[i] = s / 32768.0f;
        }
        return out;
    }
}
