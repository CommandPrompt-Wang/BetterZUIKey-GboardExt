package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;

/**
 * 语音引擎宿主（P0）。
 *
 * <p><b>它干什么</b>：把「Gboard 要一个识别器」这件事接过来，换成"我们的音频 + 我们的引擎脚本"，
 * 再把脚本产出的文字翻译回 Gboard 的结果对象。四件事：
 * <ol>
 *   <li>结构化定位挂点（{@code SpeechRecognitionFacilitator.java} 锚点 → 返回引擎接口的方法）；</li>
 *   <li>引擎 id 为空 ⇒ <b>完全不接管</b>（透传，零配置零回归）；</li>
 *   <li>会话：起 HandlerThread → 加载脚本 → 开麦 → 喂帧；</li>
 *   <li>收尾：停麦 → 脚本 stop() → 等最后一次 final → 释放。</li>
 * </ol>
 *
 * <p><b>线程纪律</b>：Rhino 的 Context 不跨线程，所以脚本的一切调用
 * （启动、音频、定时器、以后的 WS 回调）都必须投递到<b>同一个</b> {@code bzk-voice-engine} 线程。
 * 音频线程只做"拷贝一帧 + post"。
 */
final class VoiceEngineHost {

    static final String TAG = "GboardExt";
    static final boolean DEV_TRACE = true;

    /** 会话上限：对齐讯飞流式听写的 60s。 */
    private static final long SESSION_MAX_MS = 60_000;

    /** 停止后等"最后一次 final"的时间。 */
    private static final long STOP_GRACE_MS = 1_500;

    private static volatile boolean sInstalled;
    private static volatile XposedModule sModule;
    private static volatile Context sCtx;
    private static volatile String sEngineId = "";
    private static volatile String sEngineLabel = "";
    private static volatile String sEngineScript = "";
    private static volatile String sEngineConfig = "{}";
    /** 白名单原文（JSON 数组串，只用于比较变化）；空串 = 没声明过。 */
    private static volatile String sEngineHostsJson = "";
    /** 「启用标点切分」（广播推来的开关状态）。 */
    private static volatile boolean sOfflineVad;
    /**
     * 白名单解析结果：脚本只许连这些域名/IP（空集合 = 一个都不许连）。
     * {@code null} = 配置里**没有声明过**白名单（上个版本推的旧配置）⇒ 不做限制，
     * 免得"升级模块但没开设置页"直接变成连不上（下次一推就转成严格校验）。
     */
    private static volatile java.util.Set<String> sEngineHosts;
    private static volatile boolean sVoiceEnabled;
    private static volatile Object sProxy;

    // 一次只有一个会话
    private static volatile Object sCallback;
    private static volatile AudioSource sAudio;
    private static volatile HandlerThread sThread;
    private static volatile Handler sHandler;
    private static volatile ScriptEngine sScript;
    private static volatile boolean sRunning;
    /** 本次会话是否用了离线本地识别（决定 final 怎么提交，见 Sink.finalText）。 */
    private static volatile boolean sLocalAsrUsed;
    /**
     * 已经请求停止（{@link #stopSession} 置位，{@link #startSession} 清掉）。
     *
     * <p><b>停止之后一律不再接受新的部分结果</b>：Gboard 在 {@code stop} 那一刻就把已有文字
     * 落盘了，之后再来的 partial（排队积压 / 云端迟到）会**接在已上屏的文字后面**，于是一句话
     * 变成两截重叠（实测症状："四十二"→"四十二二"）。最终结果仍照发（离线结果本来就晚，见
     * {@code Sink.finalText}）。
     */
    private static volatile boolean sStopRequested;

    // ---- 音频帧队列（**有界**，见 onFrame 的注释）----
    /** 队列里最多排多少帧（8 × 40ms = 320ms）。 */
    private static final int FRAME_QUEUE_MAX = 8;
    private static final Object FRAME_TOKEN = new Object();
    private static final java.util.ArrayDeque<byte[]> sFrames = new java.util.ArrayDeque<>();
    private static int sFrameDrops;
    private static boolean sFrameDrainPosted;

    /**
     * 把排队的音频帧喂给脚本。**同一时刻最多只有一个 drain 在队列里**（否则又变成无界积压），
     * 一次 drain 会把当时排到的帧都喂完。
     */
    private static void drainFrames() {
        while (true) {
            final byte[] f;
            synchronized (sFrames) {
                f = sFrames.pollFirst();
                if (f == null) {
                    sFrameDrainPosted = false;
                    break;
                }
            }
            final ScriptEngine sc = sScript;
            if (sc == null) {
                dropPendingFrames();
                return;
            }
            sc.audio(f, f.length);
        }
        synchronized (sFrames) {
            if (sFrameDrops > 0 && sFrameDrops % 50 < 1) {
                Log.w(TAG, "voice: 引擎跟不上，已丢 " + sFrameDrops + " 帧音频（每帧 40ms）");
            }
        }
    }

    /** 丢掉所有还没喂的帧（停止会话时用：那些帧属于"已经结束的那句话"）。 */
    private static void dropPendingFrames() {
        synchronized (sFrames) {
            final int n = sFrames.size();
            sFrames.clear();
            sFrameDrainPosted = false;
            if (n > 0) Log.i(TAG, "voice: 丢掉还没喂的 " + n + " 帧（会话已结束）");
        }
        final Handler h = sHandler;
        if (h != null) h.removeCallbacksAndMessages(FRAME_TOKEN);
    }

    /** 最后一次 partial（停止时如果云端还没给 final，就把它当 final 发出去，见 stopSession）。 */
    private static volatile String sLastPartial = "";
    /** 上一次记电平日志的时刻（每秒一行，见 onLevel）。 */
    private static long sLastLevelLog;
    private static volatile boolean sFinaled;

    private VoiceEngineHost() {
    }

    /** 生效的脚本正文：优先用广播推来的；没有就按 {@code builtin-xxx → xxx.js} 从 APK assets 兜底。 */
    private static volatile String sAssetScript = "";
    private static volatile String sAssetFor = "";

    private static String effectiveScript() {
        if (!sEngineScript.isEmpty()) return sEngineScript;
        final String id = sEngineId;
        if (id.isEmpty()) return "";
        if (!id.equals(sAssetFor)) {
            final String file = (id.startsWith("builtin-") ? id.substring(8) : id) + ".js";
            sAssetScript = loadAsset("engines/" + file);
            if (sAssetScript == null) sAssetScript = "";
            sAssetFor = id;
        }
        return sAssetScript;
    }

    /** 接管条件：总开关开 + 选了配置 + 有可用脚本（广播推来的，或内置 assets 兜底）。 */
    static boolean enabled() {
        return sVoiceEnabled && !sEngineId.isEmpty() && !effectiveScript().isEmpty();
    }

    // ------------------------------------------------------------------ 安装

    static void install(XposedModule module, ClassLoader cl, Context ctx) {
        LocalAsr.setModule(module);        // 离线 VAD 模型要从模块 APK 里抽
        if (sInstalled) return;
        sInstalled = true;
        sModule = module;
        sCtx = ctx;
        reloadEngine();
        try {
            final String apk = ctx.getPackageManager()
                    .getApplicationInfo(BridgeHook.TARGET_PKG, 0).sourceDir;
            if (!ServiceProbe.loadDexKitNative(ctx)) {
                Log.w(TAG, "voice: dexkit native 不可用，放弃接管");
                return;
            }
            final DexKitBridge bridge = DexKitBridge.create(apk);
            try {
                // 1) 锚点：谁打印 "SpeechRecognitionFacilitator.java"
                Class<?> klu = null;
                Class<?> klt = null;
                Class<?> kls = null;
                Method getRecognizer = null;
                final ClassDataList facs = bridge.findClass(FindClass.create().matcher(
                        ClassMatcher.create().usingStrings("SpeechRecognitionFacilitator.java")));
                Log.i(TAG, "voice: 锚点命中 " + facs.size() + " 个类");
                for (ClassData cd : facs) {
                    final Class<?> c = cd.getInstance(cl);
                    for (Method m : c.getDeclaredMethods()) {
                        final Class<?> r = m.getReturnType();
                        if (r.isInterface() && r.getDeclaredMethods().length >= 5
                                && m.getParameterCount() >= 1) {
                            getRecognizer = m;
                            klu = r;
                            break;
                        }
                    }
                    if (klu != null) break;
                }
                if (klu == null) {
                    Log.w(TAG, "voice: 没找到「返回引擎接口」的方法，放弃接管");
                    return;
                }
                // 2) 引擎接口里：0 参返回枚举 = 档位；参数里的 ≥5 方法接口 = 回调
                for (Method m : klu.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().isEnum()) klt = m.getReturnType();
                    for (Class<?> p : m.getParameterTypes()) {
                        if (p.isInterface() && p.getDeclaredMethods().length >= 5) kls = p;
                    }
                }
                if (kls == null) {
                    Log.w(TAG, "voice: 没找到回调接口，放弃接管");
                    return;
                }
                // 3) 结果类 = 回调里"单对象参数"方法的参数类型（实测：kls#h 的参数）
                Class<?> aaeo = null;
                for (Method m : kls.getDeclaredMethods()) {
                    final Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && !ps[0].isPrimitive() && !ps[0].isInterface()
                            && !ps[0].isEnum() && !ps[0].isArray()) {
                        aaeo = ps[0];
                        break;
                    }
                }
                if (aaeo == null) {
                    Log.w(TAG, "voice: 没找到结果类，放弃接管");
                    return;
                }
                GboardSink.init(bridge, aaeo, kls, aaeo.getSuperclass().getName());
                if (!GboardSink.ready()) {
                    Log.w(TAG, "voice: 结果对象映射不可用（" + GboardSink.why() + "），放弃接管");
                    return;
                }
                sProxy = RecognizerProxy.create(klu, klt);
                getRecognizer.setAccessible(true);
                module.hook(getRecognizer).intercept(chain -> {
                    if (!enabled()) {
                        if (DEV_TRACE) {
                            Log.i(TAG, "voice: 透传（enabled=" + sVoiceEnabled + " id=\"" + sEngineId
                                    + "\" script=" + effectiveScript().length() + "B）");
                        }
                        return chain.proceed();                  // 默认：透传
                    }
                    if (DEV_TRACE) Log.i(TAG, "voice: 接管 " + chain.getExecutable().getName());
                    return sProxy;
                });
                // 离线语音：native 加载自检（开发期，见 OfflineSelfTest）
                OfflineSelfTest.run(ctx, sModule);
                Log.i(TAG, "voice: installed, engine=\"" + sEngineId + "\" klu=" + klu.getName()
                        + " kls=" + kls.getName() + " aaeo=" + aaeo.getName()
                        + " klt=" + (klt != null ? klt.getName() : "-"));
            } finally {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "voice: install 失败: " + tr);
        }
    }

    /** 广播里改了引擎 id 时调用（下次会话生效）。 */
    static void reloadEngine() {
        try {
            final Context c = sCtx != null ? sCtx : GboardState.context();
            if (c == null) return;
            final SharedPreferences sp = c.getSharedPreferences(
                    BroadcastConfig.STATE_PREFS, Context.MODE_PRIVATE);
            final String id = sp.getString(GboardConfig.KEY_ENGINE, "");
            final String label = sp.getString("voiceEngineLabel", "");
            final String script = sp.getString("voiceEngineScript", "");
            final String vcfg = sp.getString("voiceEngineConfig", "{}");
            final boolean hasHosts = sp.contains("voiceEngineHosts");
            final String vhosts = hasHosts ? sp.getString("voiceEngineHosts", "[]") : "";
            final boolean on = sp.getBoolean(GboardConfig.KEY_VOICE_ENABLED, false);
            // 「启用标点切分」：VAD 模型随 APK 打包，这里只同步开关状态（每次会话都要读，
            // 因为它可以在开关被点后立刻生效，不参与上面的"变化检测"）
            sOfflineVad = sp.getBoolean("offlineVad", false);
            LocalAsr.setPunctEnabled(sp.getBoolean("offlinePunct", false));
            // 注意：**配置内容（engineConfig）也必须参与检测** —— 踩过：同一个引擎里只改表单值
            // （例如把火山的 Resource-Id 从 1.0 换成 2.0）时 id/脚本长度/开关/白名单都没变，
            // 于是 sEngineConfig 一直留着旧值，脚本拿到的还是老配置（表现：明明改了、日志里还是旧值）。
            if (!id.equals(sEngineId) || script.length() != sEngineScript.length() || on != sVoiceEnabled
                    || !vhosts.equals(sEngineHostsJson) || !vcfg.equals(sEngineConfig)) {
                Log.i(TAG, "voice: enabled=" + on + " engine \"" + sEngineId + "\" -> \"" + id
                        + "\" (" + label + ", script " + sEngineScript.length() + " 字符 -> "
                        + script.length() + "B, config " + sEngineConfig.length() + " -> "
                        + vcfg.length() + "B, hosts="
                        + (hasHosts ? vhosts : "未声明(不限制)") + ")");
                sEngineId = id;
                sEngineLabel = label;
                sEngineScript = script;
                sEngineConfig = vcfg;
                sEngineHostsJson = vhosts;
                sEngineHosts = hasHosts ? parseHosts(vhosts) : null;
                sVoiceEnabled = on;
            }
            // 离线档位就顺手预热：Gboard 进程一起来（或用户刚换档位）就开始加载，
            // 别等按下语音键才现加载 —— 24MiB 档 2.3s、189MiB 档 4~7.5s，那就是"启动延迟"。
            // preload 是幂等的（已加载/正在加载会直接返回），所以每次广播都调也没代价。
            preloadOfflineModel(id);
        } catch (Throwable tr) {
            Log.w(TAG, "voice: reloadEngine 失败: " + tr);
        }
    }

    /** 离线档位 → 本地模型目录名（不是离线档位返回空串）。 */
    private static String offlineModelOf(String engineId) {
        switch (engineId == null ? "" : engineId) {
            case "builtin-sensevoice":   return "sensevoice";
            case "builtin-paraformer":   return "paraformer";
            case "builtin-zipformer-zh": return "zipformer-zh";
            case "builtin-zipformer-bi": return "zipformer-bi";
            default: return "";
        }
    }

    /**
     * 预热当前选中的离线档位（后台加载）。**模型还没同步下来就跳过** —— 否则每次广播都会
     * 打一条"预热失败"（模型没到本地）。加载完还会排一次空闲释放，免得白占内存。
     */
    private static void preloadOfflineModel(String engineId) {
        final String model = offlineModelOf(engineId);
        if (model.isEmpty()) return;
        final Context c = sCtx != null ? sCtx : GboardState.context();
        if (c == null) return;
        final File dir = OfflineModels.dirOf(c, model);
        final String main = model.startsWith("zipformer")
                ? "encoder-epoch-99-avg-1.int8.onnx" : "model.int8.onnx";
        if (!new File(dir, main).isFile()) return;
        LocalAsr.preload(c, model);
        scheduleIdleRelease();
    }

    /** 广播里下发的白名单 JSON → Set（小写、去空）。解析失败按**空名单**处理（宁可连不上，不可越权）。 */
    private static java.util.Set<String> parseHosts(String json) {
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            final JSONArray arr = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                final String h = arr.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT);
                if (!h.isEmpty()) out.add(h);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "voice: 白名单解析失败（按空名单处理）: " + tr);
        }
        return out;
    }

    // ------------------------------------------------------------------ 会话

    static void startSession(Object sessionParams, Object callback) {
        if (!enabled()) return;
        reloadEngine();
        if (!enabled()) return;
        if (sRunning) stopSession("restart");

        final String lang = readLanguage(sessionParams);
        GboardSink.setLanguage(lang);
        sCallback = callback;
        sRunning = true;
        sLastPartial = "";
        sFinaled = false;
        sLocalAsrUsed = false;
        sStopRequested = false;
        GboardSink.onStart(callback);      // f() + a()/c()（见 GboardSink.onStart 的注释）

        final HandlerThread ht = new HandlerThread("bzk-voice-engine");
        ht.start();
        sThread = ht;
        final Handler h = new Handler(ht.getLooper());
        sHandler = h;

        h.post(() -> {
            final Sink sink = new Sink();
            final android.content.Context gctx = sCtx != null ? sCtx : GboardState.context();
            final ScriptEngine script = new ScriptEngine(sink, h, sEngineHosts, gctx);
            script.setVadEnabled(sOfflineVad);
            sScript = script;
            final String src = effectiveScript();
            if (src == null || src.isEmpty()) {
                sink.fail("CONFIG", "没有可用的脚本（engine=" + sEngineId + "）");
                return;
            }
            if (!script.load(src, sEngineId + ".js", engineConfig(), lang,
                    16000, AudioSource.FRAME_BYTES)) {
                return;         // 失败原因已由 ScriptEngine 走 sink.fail 报出去并收尾
            }
            final AudioSource audio = new AudioSource(new AudioSource.Sink() {
                @Override
                public void onFrame(byte[] buf, int len) {
                    // 音频线程只做"拷一帧 + 入队"，脚本只在引擎线程跑。
                    //
                    // **有界队列**（踩过的大坑）：原来是一帧一个 h.post，引擎线程一旦忙不过来
                    // （流式模型加载 7.5s / 增量解码跟不上），队列就无上限地堆 —— 实测积压 20+ 秒，
                    // 用户停止后引擎还在往外发"部分结果"、23 秒后才提交最终文本，于是**新结果和
                    // 已经上屏的文字重叠**（表现就是"四十二"变成"四十二二"）。
                    // 现在：只留最新 8 帧（320ms），超了丢最旧的 —— 实时语义宁可漏一点音频。
                    final byte[] copy = Arrays.copyOf(buf, len);
                    boolean post = false;
                    synchronized (sFrames) {
                        if (!sRunning) return;              // 会话已结束：晚到的帧直接丢
                        sFrames.addLast(copy);
                        while (sFrames.size() > FRAME_QUEUE_MAX) {
                            sFrames.removeFirst();
                            sFrameDrops++;
                        }
                        if (!sFrameDrainPosted) {
                            sFrameDrainPosted = true;
                            post = true;
                        }
                    }
                    if (post) {
                        final Handler hh = sHandler;
                        // 带 token 投递（postAtTime 的 uptime=0 ⇒ 立刻执行），这样停止会话时
                        // 可以 removeCallbacksAndMessages(FRAME_TOKEN) 把还没喂的帧一次清掉
                        if (hh != null) hh.postAtTime(VoiceEngineHost::drainFrames, FRAME_TOKEN, 0L);
                    }
                }

                @Override
                public void onLevel(int level) {
                    GboardSink.level(sCallback, level);
                    // 每秒记一次电平：排查"开头几秒没出字"时，先确认**麦到底有没有收到声音**
                    // （Gboard 那个语音气泡的波形就是这条路，所以日志与波形应当一致）
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (now - sLastLevelLog > 1000) {
                        sLastLevelLog = now;
                        Log.i(TAG, "voice: 电平 " + level);
                    }
                }

                @Override
                public void onEnd() {
                }

                @Override
                public void onError(String raw) {
                    // 采集线程 → 引擎线程（脚本/结果出口都只认引擎线程）
                    final Handler hh = sHandler;
                    if (hh != null) hh.post(() -> sink.fail("AUDIO", raw));
                }
            });
            sAudio = audio;
            if (!audio.start()) {
                sink.fail("AUDIO", audio.why());
                return;
            }
            script.start();
            // 实测：a()/c() 这对回调在第一条结果之前会出现两次 ⇒ 再补一次
            GboardSink.pair(sCallback);
        });
        h.postDelayed(() -> {
            Log.w(TAG, "voice: 会话超过 " + SESSION_MAX_MS + "ms，强制收尾");
            stopSession("timeout");
        }, SESSION_MAX_MS);
        if (DEV_TRACE) {
            Log.i(TAG, "voice: session start, engine=" + sEngineId + " lang=" + lang);
            // 把会话参数打出来：里面 triggerApplicationId / surroundingText 能看出
            // "这一轮到底有没有拿到焦点输入框"（没有输入框时 Gboard 照样跑，但结果无处可写）
            String sp = String.valueOf(sessionParams);
            Log.i(TAG, "voice: sessionParams=" + (sp.length() > 320 ? sp.substring(0, 320) + "…" : sp));
        }
    }

    static void stopSession(String why) {
        if (!sRunning) return;
        final Handler h = sHandler;
        if (h == null) {
            finish();
            return;
        }
        if (DEV_TRACE) Log.i(TAG, "voice: stop (" + why + ")");
        sStopRequested = true;               // 停止之后不再接受任何迟到的部分结果
        final AudioSource audio = sAudio;
        sAudio = null;
        if (audio != null) audio.stop();      // 先停麦，避免结束帧后面还夹着尾音
        // 还没喂的帧**立刻丢掉**：它们是"已经结束的这句话"的尾巴。不丢的话，sc.stop() 会排在它们
        // 后面执行，实测拖到 23 秒后才提交最终文本（结果和已上屏的文字重叠）。
        dropPendingFrames();
        h.post(() -> {
            final ScriptEngine sc = sScript;
            if (sc != null) sc.stop();        // 脚本发结束帧（讯飞据此给最后一片结果）
        });
        // **兜底**：云端结果常常比"用户按停"晚到（实测讯飞晚了 0.5s，而 Gboard 那时已经关了会话
        // ⇒ 最终结果被丢掉，表现是"说了话但什么都没上屏"）。所以给云端一个短窗口，
        // 到点还没 final 就把最后一次 partial 当 final 发出去。
        h.postDelayed(() -> {
            if (!sFinaled && sLastPartial != null && !sLastPartial.isEmpty()) {
                Log.i(TAG, "voice: 用最后一次 partial 兜底成 final: \"" + sLastPartial + "\"");
                GboardSink.finalText(sCallback, sLastPartial, 0.0);
                sFinaled = true;
            }
        }, 400);
        h.postDelayed(VoiceEngineHost::finish, STOP_GRACE_MS);
    }

    static void cancelSession() {
        if (DEV_TRACE) Log.i(TAG, "voice: cancel");
        finish();
    }

    private static void finish() {
        if (!sRunning && sScript == null) return;
        sRunning = false;
        final AudioSource audio = sAudio;
        sAudio = null;
        if (audio != null) audio.stop();
        dropPendingFrames();
        final ScriptEngine sc = sScript;
        sScript = null;
        if (sc != null) sc.dispose();
        try {
            GboardSink.finished(sCallback);
        } catch (Throwable ignored) {
        }
        sCallback = null;
        final HandlerThread ht = sThread;
        sThread = null;
        sHandler = null;
        if (ht != null) ht.quitSafely();
        scheduleIdleRelease();      // 离线模型很吃内存（见 LocalAsr），空闲一段时间后释放
        if (DEV_TRACE) Log.i(TAG, "voice: session finished");
    }

    /** 会话结束后排一次"空闲释放离线 recognizer"（一个后台线程睡够再查，查完即退）。 */
    private static volatile boolean sIdleScheduled;

    static void scheduleIdleRelease() {
        if (sIdleScheduled) return;
        sIdleScheduled = true;
        final Thread t = new Thread(() -> {
            try {
                // 每分钟查一次，最多看 20 分钟；各档位自己的窗口在 LocalAsr.idleWindow() 里
                // （小档位 10 分钟、大档位 3 分钟）—— 所以这里不能只睡 65 秒。
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(60_000);
                    LocalAsr.releaseIfIdle();
                    if (!LocalAsr.hasLoaded()) break;
                }
            } catch (InterruptedException ignored) {
            } finally {
                sIdleScheduled = false;
            }
        }, "bzk-offline-idle");
        t.setDaemon(true);
        t.start();
    }

    /** 引擎的结果出口。 */
    private static final class Sink implements ScriptEngine.Sink {
        @Override
        public void partial(String text) {
            if (sStopRequested) {
                // **不往 Gboard 发**（会和已落盘的组合文本重叠 —— 见上面的注释），但要**记下来**：
                // 停后到的这条往往比"最后一次已上屏的"更完整，兜底时用它，尾部才不会丢
                // （实测：屏幕上已是"…把所有的 x。"，兜底却提交了旧的"…把所有的"）。
                sLastPartial = text;
                Log.i(TAG, "voice: 会话已停止，这条部分结果只留给兜底用：\"" + text + "\"");
                return;
            }
            sLastPartial = text;
            // 注意：**不能**用输入连接写组合文本 —— Gboard 会当成"选区变化"从而结束语音会话
            // （实测：日志里 `voice: stop (SELECTION_CHANGE)`，说一句就断）。所以 partial 一律
            // 走结果通道；只有会话结束后的**最终**提交才走输入连接（见 finalText）。
            GboardSink.partial(sCallback, text);
        }

        @Override
        public void finalText(String text, double conf) {
            sFinaled = true;
            // 离线识别：结果比语音会话晚几秒，Gboard 已经不认结果通道了（实测没上屏）
            // ⇒ 直接用输入连接提交。提交失败再回退常规通道。
            if (sLocalAsrUsed && GboardSink.commitDirect(text)) return;
            GboardSink.finalText(sCallback, text, conf);
        }

        @Override
        public void noteLocalAsr() {
            sLocalAsrUsed = true;
        }

        @Override
        public void level(int v) {
            GboardSink.level(sCallback, v);
        }

        @Override
        public void fail(String code, String msg) {
            final String raw = (msg == null || msg.trim().isEmpty())
                    ? String.valueOf(code) : msg;
            // 1) 原始信息**一字不改**进日志（多行、JSON、HTTP 头都照打）—— 这是排查的唯一依据
            Log.w(TAG, "voice: 出现错误 [" + code + "] " + raw);
            // 2) 上屏：用户口径「出现错误：{原始错误内容}」，然后结束这次听写。
            //    走 finalText ⇒ commitText，且它内部会回调结束（mg()），Gboard 那侧随即收尾。
            //    截断/压行只影响上屏（日志里是全文）：换行在输入框里可能触发"发送"。
            sFinaled = true;            // 别让 400ms 的 partial 兜底把错误信息盖掉
            sLastPartial = null;
            GboardSink.finalText(sCallback, ERR_PREFIX + oneLine(raw), 0.0);
            Banner.show("语音引擎失败：" + oneLine(raw));
            finish();
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, "voice[script]: " + msg);
        }
    }

    // ------------------------------------------------------------------ 杂项

    /** 错误上屏的前缀（用户口径：出现错误：{原始错误内容}）。 */
    private static final String ERR_PREFIX = "出现错误：";
    /** 上屏长度上限：原始内容可能是整页 HTML/JSON，全塞进输入框没法用（日志里始终是全文）。 */
    private static final int ERR_SHOW_MAX = 500;

    /**
     * 错误文本 → 上屏能用的样子：多行压成一行（输入框里换行可能触发"发送"）、过长截断。
     * 只影响上屏，日志里永远是原文。
     */
    private static String oneLine(String s) {
        if (s == null) return "";
        final String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= ERR_SHOW_MAX
                ? t : t.substring(0, ERR_SHOW_MAX) + "…（完整内容见日志）";
    }

    /** 从 kma（会话参数）里读语言标签：找一个形如 zh-CN 的 String 字段（不写死字段名）。 */
    private static String readLanguage(Object sessionParams) {
        try {
            for (Field f : sessionParams.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                final Object v = f.get(sessionParams);
                if (v instanceof String && ((String) v).matches("[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*")) {
                    return (String) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return "zh-CN";
    }

    /** 设置页表单填的值（engine.input.*）→ Map。</br>
     *  与脚本里的 engine.config 合并时，**表单优先**（脚本给默认、用户填真值）。 */
    private static Map<String, String> engineConfig() {
        final Map<String, String> out = new HashMap<>();
        try {
            final org.json.JSONObject o = new org.json.JSONObject(sEngineConfig);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                final String k = it.next();
                out.put(k, o.optString(k, ""));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 从**模块 APK** 的 assets 里读脚本（Gboard 进程里拿不到我们的 AssetManager）。 */
    private static String loadAsset(String path) {
        try {
            final XposedModule m = sModule;
            if (m == null || m.getModuleApplicationInfo() == null) return null;
            final String apk = m.getModuleApplicationInfo().sourceDir;
            try (ZipFile zf = new ZipFile(apk)) {
                final ZipEntry e = zf.getEntry("assets/" + path);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) {
                    final byte[] buf = new byte[(int) e.getSize()];
                    int off = 0;
                    while (off < buf.length) {
                        final int n = in.read(buf, off, buf.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                    return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "voice: 读脚本失败 " + path + ": " + tr);
            return null;
        }
    }
}
