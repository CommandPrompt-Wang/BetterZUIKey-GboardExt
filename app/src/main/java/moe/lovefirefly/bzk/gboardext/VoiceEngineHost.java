package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

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
    private static volatile boolean sVoiceEnabled;
    private static volatile Object sProxy;

    // 一次只有一个会话
    private static volatile Object sCallback;
    private static volatile AudioSource sAudio;
    private static volatile HandlerThread sThread;
    private static volatile Handler sHandler;
    private static volatile ScriptEngine sScript;
    private static volatile boolean sRunning;
    /** 最后一次 partial（停止时如果云端还没给 final，就把它当 final 发出去，见 stopSession）。 */
    private static volatile String sLastPartial = "";
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
            final boolean on = sp.getBoolean(GboardConfig.KEY_VOICE_ENABLED, false);
            if (!id.equals(sEngineId) || script.length() != sEngineScript.length() || on != sVoiceEnabled) {
                Log.i(TAG, "voice: enabled=" + on + " engine \"" + sEngineId + "\" -> \"" + id
                        + "\" (" + label + ", script " + sEngineScript.length() + "B -> "
                        + script.length() + "B)");
                sEngineId = id;
                sEngineLabel = label;
                sEngineScript = script;
                sEngineConfig = vcfg;
                sVoiceEnabled = on;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "voice: reloadEngine 失败: " + tr);
        }
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
        GboardSink.onStart(callback);      // f() + a()/c()（见 GboardSink.onStart 的注释）

        final HandlerThread ht = new HandlerThread("bzk-voice-engine");
        ht.start();
        sThread = ht;
        final Handler h = new Handler(ht.getLooper());
        sHandler = h;

        h.post(() -> {
            final ScriptEngine script = new ScriptEngine(new Sink(), h);
            sScript = script;
            final String src = effectiveScript();
            if (src == null || src.isEmpty()) {
                Log.w(TAG, "voice: 没有可用的脚本（engine=" + sEngineId + "）");
                finish();
                return;
            }
            if (!script.load(src, sEngineId + ".js", engineConfig(), lang,
                    16000, AudioSource.FRAME_BYTES)) {
                finish();
                return;
            }
            final AudioSource audio = new AudioSource(new AudioSource.Sink() {
                @Override
                public void onFrame(byte[] buf, int len) {
                    // 音频线程只做"拷一帧 + 投递"，脚本只在引擎线程跑
                    final byte[] copy = Arrays.copyOf(buf, len);
                    final Handler hh = sHandler;
                    if (hh != null) hh.post(() -> {
                        final ScriptEngine sc = sScript;
                        if (sc != null) sc.audio(copy, copy.length);
                    });
                }

                @Override
                public void onLevel(int level) {
                    GboardSink.level(sCallback, level);
                }

                @Override
                public void onEnd() {
                }
            });
            sAudio = audio;
            if (!audio.start()) {
                finish();
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
        final AudioSource audio = sAudio;
        sAudio = null;
        if (audio != null) audio.stop();      // 先停麦，避免结束帧后面还夹着尾音
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
        if (DEV_TRACE) Log.i(TAG, "voice: session finished");
    }

    /** 引擎的结果出口。 */
    private static final class Sink implements ScriptEngine.Sink {
        @Override
        public void partial(String text) {
            sLastPartial = text;
            GboardSink.partial(sCallback, text);
        }

        @Override
        public void finalText(String text, double conf) {
            sFinaled = true;
            GboardSink.finalText(sCallback, text, conf);
        }

        @Override
        public void level(int v) {
            GboardSink.level(sCallback, v);
        }

        @Override
        public void fail(String code, String msg) {
            Log.w(TAG, "voice: engine fail " + code + ": " + msg);
            Banner.show("语音引擎失败：" + msg);
            finish();
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, "voice[script]: " + msg);
        }
    }

    // ------------------------------------------------------------------ 杂项

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
