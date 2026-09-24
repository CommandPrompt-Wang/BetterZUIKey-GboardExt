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
 * <p><b>非流式</b>：SenseVoice 只有整段解码，所以录音期间没有部分结果 —— 脚本把帧缓存到
 * {@code engine.stop} 时一次解码（这也是 §19.2 里 "SenseVoice = 非流式" 的直接后果）。
 *
 * <p><b>内存</b>：模型加载后要占几百 MB（实测机型可用内存本就紧张），所以空闲
 * {@link #IDLE_RELEASE_MS} 后释放 recognizer；线程数也压到 {@link #THREADS}（§19.2 的结论）。
 */
final class LocalAsr {

    private static final String TAG = "GboardExt";

    private static final int SAMPLE_RATE = 16000;
    private static final int THREADS = 6;
    /** 空闲多久释放 recognizer（内存换时间；下次用会重新加载 ~1s）。 */
    private static final long IDLE_RELEASE_MS = 60_000;
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
            final String text = decodeSamples(ctx, modelId, toFloats(pcm));
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
        final Thread t = new Thread(() -> {
            try {
                synchronized (LOCK) {
                    if (sRec != null && modelId.equals(sRecModel)) return;
                }
                recognizer(ctx, modelId);
            } catch (Throwable tr) {
                Log.w(TAG, "offline-asr: 预热失败: " + tr);
            }
        }, "bzk-offline-preload");
        t.setDaemon(true);
        t.start();
    }

    static void setModule(XposedModule m) {
        sModule = m;
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
        return changed ? sStreamText.toString() : "";
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
        return sStreamText.toString();
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
            if (sRec != null && SystemClock.uptimeMillis() - sLastUse > IDLE_RELEASE_MS) {
                Log.i(TAG, "offline-asr: 空闲释放 recognizer（" + sRecModel + "）");
                releaseLocked();
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

    private static void releaseLocked() {
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
