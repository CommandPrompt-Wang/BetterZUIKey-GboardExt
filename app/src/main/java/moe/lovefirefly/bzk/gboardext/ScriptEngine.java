package moe.lovefirefly.bzk.gboardext;

import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 引擎脚本宿主（Rhino）。
 *
 * <p><b>为什么用 Rhino</b>：纯 Java、无 native（模块已经在手工加载 {@code libdexkit.so}，
 * 能不加 .so 就不加）；字节码版本 52（Java 8），D8/ART 直接吃。
 *
 * <p><b>三个 Android/Rhino 的硬约束</b>：
 * <ol>
 *   <li>{@code setOptimizationLevel(-1)}：Android 上不能让它生成字节码（没有可用的
 *       {@code defineClass} 路径），必须解释执行；解释模式下指令观察器也才有效；</li>
 *   <li><b>线程</b>：Rhino 的 {@code Context} 不跨线程 —— 所有脚本调用（含 WS 回调、
 *       {@code after} 定时器）都必须由调用方投递到<b>同一个</b> Handler 线程；</li>
 *   <li><b>沙箱</b>：{@code ClassShutter} 全拒 + 自定义 {@code WrapFactory} 拒绝把任何
 *       Java 对象包给 JS + 删掉 {@code Packages/java/load/importClass/getClass} 等顶层属性；
 *       脚本能摸到的只有我们造的 {@code ctx}。</li>
 * </ol>
 *
 * <p>脚本约定：作用域里预置一个空的 {@code engine} 对象，脚本往里挂
 * {@code engine.start/audio/stop}（都是 ES5 函数）。所有异常都吞在这里转成 {@code fail}。
 */
final class ScriptEngine {

    private static final String TAG = "GboardExt";

    /** 单次脚本调用的指令预算（解释执行下约等于几十毫秒）。 */
    private static final int INSTRUCTION_BUDGET = 300_000;

    interface Sink {
        void partial(String text);

        void finalText(String text, double conf);

        void level(int v);

        void fail(String code, String msg);

        /** 本次会话用了离线本地识别（结果会比会话晚，宿主需要换一种提交方式）。 */
        void noteLocalAsr();

        void log(String msg);
    }

    private final Sandbox factory = new Sandbox();
    private final Sink sink;
    private final Handler handler;

    private Scriptable scope;
    private Scriptable engineObj;
    private volatile boolean loaded;

    /**
     * 允许脚本连的域名/IP：**空集合 = 一个都不许连**；
     * {@code null} = 宿主没声明过白名单（旧配置）⇒ 不校验（见 VoiceEngineHost.sEngineHosts）。
     */
    private final java.util.Set<String> allowedHosts;
    /** Gboard 进程的 Context（离线模型在它自己的 files 目录里）。 */
    private final android.content.Context appCtx;

    ScriptEngine(Sink sink, Handler handler, java.util.Set<String> allowedHosts,
            android.content.Context appCtx) {
        this.sink = sink;
        this.handler = handler;
        this.allowedHosts = allowedHosts;
        this.appCtx = appCtx;
    }

    boolean loaded() {
        return loaded;
    }

    /**
     * 编译脚本并造好 {@code ctx}。
     *
     * @return false = 引擎不可用（调用方回退透传）
     */
    boolean load(String script, String name, Map<String, String> config,
            String languageTag, int sampleRate, int frameBytes) {
        try {
            final Context cx = factory.enterContext();
            try {
                cx.setOptimizationLevel(-1);      // 见类注释 ①
                scope = cx.initStandardObjects();
                stripDangerous(scope);
                // 预先建好 engine / engine.input / engine.config：
                // 脚本可以直接写 `engine.input.appid = ""`（不用自己先 new 一个对象）——
                // 踩过：没预建时 `engine.input.appid = …` 报 "Cannot set property of undefined"
                final Scriptable eng = cx.newObject(scope);
                eng.put("input", eng, cx.newObject(scope));
                eng.put("config", eng, cx.newObject(scope));
                ScriptableObject.putProperty(scope, "engine", eng);
                cx.evaluateString(scope, script, name, 1, null);
                final Object e = scope.get("engine", scope);
                if (!(e instanceof Scriptable)) {
                    sink.fail("SCRIPT", "脚本没有定义 engine 对象");
                    return false;
                }
                engineObj = (Scriptable) e;
                // ctx 在脚本求值**之后**再建：这样 engine.config 里写的配置（appid/apiKey/…）
                // 也能进 ctx.config —— P2 阶段没有配置表单，用户就是"复制代码→填 key→粘回导入"。
                final Map<String, String> merged = new java.util.LinkedHashMap<>();
                merged.putAll(scriptConfig());        // 脚本里写的默认值
                if (config != null) merged.putAll(config);   // 设置页填的覆盖它

                ScriptableObject.putProperty(scope, "ctx",
                        buildCtx(cx, merged, languageTag, sampleRate, frameBytes));
                loaded = true;
                Log.i(TAG, "script loaded: " + name + " id=" + str(engineObj.get("id", engineObj)));
                return true;
            } finally {
                Context.exit();
            }
        } catch (Throwable tr) {
            sink.fail("SCRIPT", "脚本加载失败: " + raw(tr));
            Log.w(TAG, "script load failed: " + tr);
            return false;
        }
    }

    void start() {
        call("start", new Object[] { ctxObj() });
    }

    void audio(byte[] pcm, int len) {
        call("audio", new Object[] { new Frame(pcm, len) });
    }

    void stop() {
        call("stop", new Object[0]);
    }

    void dispose() {
        loaded = false;
        engineObj = null;
        scope = null;
    }

    // ------------------------------------------------------------------ 调用

    private Scriptable ctxObj() {
        final Object o = scope.get("ctx", scope);
        return o instanceof Scriptable ? (Scriptable) o : scope;
    }

    private void call(String fn, Object[] args) {
        final Scriptable eng = engineObj;
        if (!loaded || eng == null) return;
        try {
            final Object f = eng.get(fn, eng);
            if (!(f instanceof Function)) return;
            final Context cx = factory.enterContext();
            try {
                factory.deadlineMs = System.currentTimeMillis() + 500;
                ((Function) f).call(cx, scope, eng, args);
            } finally {
                factory.deadlineMs = 0;
                Context.exit();
            }
        } catch (Throwable tr) {
            Log.w(TAG, "script." + fn + " 异常: " + tr);
            sink.fail("SCRIPT", fn + "() 异常: " + raw(tr));
        }
    }

    /**
     * 异常 → 原始文本。
     *
     * <p>只给 {@code getMessage()} 是不够的：脚本里抛的错经常只有一句
     * {@code TypeError: xxx is undefined}，**哪一行**才是关键 —— Rhino 的
     * {@link RhinoException#getScriptStackTrace()} 会带上 {@code file:line}。
     */
    static String raw(Throwable tr) {
        final String head = String.valueOf(tr);
        if (tr instanceof RhinoException) {
            final String st = ((RhinoException) tr).getScriptStackTrace();
            if (st != null && !st.isEmpty()) return head + "\n脚本栈:\n" + st;
        }
        return head;
    }

    // ------------------------------------------------------------------ 白名单

    /**
     * 校验脚本要连的地址是否在白名单里。合规返回 {@code null}，否则返回**给人看的原因**
     * （会走 §15.4 那条"原文上屏 + 结束听写"）。
     *
     * <p>规则（照 {@code local/VOICE-ENGINE-INTERFACE.md} §10 的口径）：
     * <ul>
     *   <li>大小写不敏感；忽略端口与路径，只看 host；</li>
     *   <li>允许<b>域名后缀</b>匹配：声明 {@code xfyun.cn} ⇒ {@code iat-api.xfyun.cn} 可以过；</li>
     *   <li>IP 字面量只做精确匹配（不做网段，避免"看着像白名单其实全放行"）；</li>
     *   <li><b>空名单 = 一个都不许连</b>（不是"不限制"）。</li>
     * </ul>
     */
    static String checkHost(java.util.Set<String> allowed, String url) {
        final String host = hostOf(url);
        if (host == null) {
            return "脚本给的网络地址无法解析：" + url + "（应形如 wss://host/path）";
        }
        if (allowed != null) {
            for (String a : allowed) {
                if (a == null) continue;
                final String t = a.trim().toLowerCase(java.util.Locale.ROOT);
                if (t.isEmpty()) continue;
                if (t.equals(host)) return null;
                if (!isIpLiteral(t) && host.endsWith("." + t)) return null;
            }
        }
        final String list = (allowed == null || allowed.isEmpty())
                ? "（空）" : String.join(", ", allowed);
        return "脚本试图连接未声明的域名 " + host + "；该配置允许的域名：" + list
                + "。要联网请单击这张卡片填「可访问域名」，或在脚本里写 engine.hosts = [\"" + host + "\"]";
    }

    /** 从 ws(s):// URL 里取 host（小写、不含端口）。取不到返回 null。 */
    static String hostOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        final int scheme = u.indexOf("://");
        if (scheme < 0) return null;
        u = u.substring(scheme + 3);
        final int end = u.length();
        int cut = end;
        for (int i = 0; i < end; i++) {
            final char ch = u.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') {
                cut = i;
                break;
            }
        }
        u = u.substring(0, cut);
        final int at = u.lastIndexOf('@');                 // user:pass@host
        if (at >= 0) u = u.substring(at + 1);
        if (u.startsWith("[")) {                          // IPv6 字面量 [::1]:443
            final int close = u.indexOf(']');
            if (close < 0) return null;
            return u.substring(1, close).toLowerCase(java.util.Locale.ROOT);
        }
        final int colon = u.indexOf(':');
        if (colon >= 0) u = u.substring(0, colon);
        u = u.trim().toLowerCase(java.util.Locale.ROOT);
        return u.isEmpty() ? null : u;
    }

    /** 粗判 IP 字面量（v4 纯数字点分 / v6 含冒号）——这类**不做后缀匹配**。 */
    private static boolean isIpLiteral(String s) {
        if (s.indexOf(':') >= 0) return true;
        return s.matches("[0-9a-fA-F.]+") && s.indexOf('.') > 0;
    }

    // ------------------------------------------------------------------ ctx 构造

    private Scriptable buildCtx(Context cx, Map<String, String> config,
            String languageTag, int sampleRate, int frameBytes) {
        final Scriptable ctx = cx.newObject(scope);

        final Scriptable session = cx.newObject(scope);
        put(cx, session, "languageTag", languageTag);
        put(cx, session, "sampleRate", sampleRate);
        put(cx, session, "channels", 1);
        put(cx, session, "bits", 16);
        put(cx, session, "frameBytes", frameBytes);
        put(cx, session, "startedAtMs", System.currentTimeMillis());
        ctx.put("session", ctx, session);

        final Scriptable cfg = cx.newObject(scope);
        if (config != null) {
            for (Map.Entry<String, String> e : config.entrySet()) {
                put(cx, cfg, e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        ctx.put("config", ctx, cfg);

        fn(cx, ctx, "log", a -> {
            sink.log(args(a, 0));
            return null;
        });
        fn(cx, ctx, "partial", a -> {
            sink.partial(args(a, 0));
            return null;
        });
        fn(cx, ctx, "finalText", a -> {
            sink.finalText(args(a, 0), num(a, 1));
            return null;
        });
        fn(cx, ctx, "level", a -> {
            sink.level((int) num(a, 0));
            return null;
        });
        fn(cx, ctx, "fail", a -> {
            sink.fail(args(a, 0), args(a, 1));
            return null;
        });
        fn(cx, ctx, "now", a -> System.currentTimeMillis());
        fn(cx, ctx, "uuid", a -> java.util.UUID.randomUUID().toString());
        fn(cx, ctx, "b64", a -> Base64.encodeToString(bytes(a, 0), Base64.NO_WRAP));
        fn(cx, ctx, "hmacSha256", a -> mac("HmacSHA256", args(a, 0), bytes(a, 1)));
        fn(cx, ctx, "hmacSha1", a -> mac("HmacSHA1", args(a, 0), bytes(a, 1)));
        fn(cx, ctx, "md5", a -> mac("HmacMD5", "", bytes(a, 0)));
        // 离线识别：脚本把录到的帧攒起来，这里一次解码（非流式模型，见 LocalAsr）
        fn(cx, ctx, "localAsr", a -> {
            final String model = args(a, 0);
            final byte[] pcm = concatFrames(a.length > 1 ? a[1] : null);
            if (pcm.length == 0) return "";
            try {
                sink.noteLocalAsr();
                return LocalAsr.decode(appCtx, model, pcm);
            } catch (Throwable tr) {
                // 失败照样走 §15.4：原文上屏 + 结束听写（脚本拿到空串就不要再 finalText）
                final String why = tr.getMessage() == null ? String.valueOf(tr) : tr.getMessage();
                sink.fail("ASR", "本地识别失败：" + why);
                return "";
            }
        });
        // 预热离线模型（会话一开始就调，别等 stop —— 见 LocalAsr.preload）
        fn(cx, ctx, "localAsrPreload", a -> {
            LocalAsr.preload(appCtx, args(a, 0));
            return null;
        });
        fn(cx, ctx, "after", a -> {
            final Object f = a.length > 1 ? a[1] : null;
            final long ms = (long) num(a, 0);
            if (f instanceof Function) {
                // 投递回同一个 Handler 线程（Rhino Context 不跨线程）
                handler.postDelayed(() -> callFn((Function) f), ms);
            }
            return null;
        });
        // WebSocket：脚本只拿到 onOpen/onMessage/onError/onClose/sendText/close
        fn(cx, ctx, "ws", a -> {
            final String url = args(a, 0);
            if (url.isEmpty()) throw new IllegalArgumentException("ws(url) 需要 url");
            final Map<String, String> hs = new java.util.LinkedHashMap<>();
            if (a.length > 1 && a[1] instanceof Scriptable) {
                final Scriptable o = (Scriptable) a[1];
                for (Object id : o.getIds()) {
                    if (id instanceof String) {
                        final Object v = o.get((String) id, o);
                        hs.put((String) id, v == null ? "" : String.valueOf(v));
                    }
                }
            }
            logHost(url);
            return new JsWs(url, hs);
        });
        return ctx;
    }

    private void callFn(Function f) {
        callFn(f, new Object[0]);
    }

    private void callFn(Function f, Object[] args) {
        if (!loaded || f == null) return;
        try {
            final Context cx = factory.enterContext();
            try {
                factory.deadlineMs = System.currentTimeMillis() + 500;
                f.call(cx, scope, engineObj, args);
            } finally {
                factory.deadlineMs = 0;
                Context.exit();
            }
        } catch (Throwable tr) {
            sink.fail("SCRIPT", "定时回调异常: " + raw(tr));
        }
    }

    /** 读脚本里声明的 {@code engine.config}（对象）→ Map。 */
    private Map<String, String> scriptConfig() {
        final Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            final Object c = engineObj.get("config", engineObj);
            if (c instanceof Scriptable) {
                final Scriptable o = (Scriptable) c;
                for (Object id : o.getIds()) {
                    if (id instanceof String) {
                        final Object v = o.get((String) id, o);
                        out.put((String) id, v == null ? "" : String.valueOf(v));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void logHost(String url) {
        try {
            final String host = java.net.URI.create(url).getHost();
            sink.log("ws: " + host);
            // 白名单强制留到 P3（配置表单那一版）：现在只记录，不拦
        } catch (Throwable ignored) {
        }
    }

    /**
     * 交给脚本的 WebSocket 对象。**连在 onOpen() 注册时才发起** —— 这样脚本有时间先把
     * onMessage/onError 挂上，不会出现"连太快、消息丢了"的竞态。
     * 所有回调都投递回引擎线程（Rhino Context 不跨线程）。
     */
    private final class JsWs extends ScriptableObject {
        private final String url;
        private final Map<String, String> headers;
        private WsClient client;
        private Function cbOpen, cbMessage, cbError, cbClose;
        private boolean started;

        JsWs(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        @Override
        public String getClassName() {
            return "Ws";
        }

        @Override
        public Object get(String name, Scriptable start) {
            switch (name) {
                case "onOpen":
                    return fn1(f -> {
                        cbOpen = asFn(f);
                        ensureStarted();
                    });
                case "onMessage":
                    return fn1(f -> cbMessage = asFn(f));
                case "onError":
                    return fn1(f -> cbError = asFn(f));
                case "onClose":
                    return fn1(f -> cbClose = asFn(f));
                case "sendText":
                    return fn1(f -> {
                        if (client != null) client.sendText(str(f));
                    });
                case "close":
                    return fn0(() -> {
                        if (client != null) client.close();
                    });
                default:
                    return super.get(name, start);
            }
        }

        private void ensureStarted() {
            if (started) return;
            started = true;
            // **白名单强制**：连接前先校验，不合规就直接失败（走统一的"原文上屏 + 结束听写"）。
            // 声明来源：内置引擎 = index.json 的 hosts；用户脚本 = 设置页填的（或脚本里的
            // engine.hosts）；空名单 = 一个都不许连，不是"不限制"。
            if (allowedHosts != null) {
                final String denied = checkHost(allowedHosts, url);
                if (denied != null) {
                    sink.fail("HOST", denied);
                    return;
                }
            }
            client = new WsClient(url, headers, new WsClient.Listener() {
                @Override public void onOpen() {
                    post(cbOpen, new Object[0]);
                }

                @Override public void onText(String text) {
                    post(cbMessage, new Object[] { text });
                }

                @Override public void onError(String msg) {
                    // 脚本没接 onError ⇒ 宿主兜底（"静默失败"是最难查的：日志里什么都没有，
                    // 用户只看到语音框自己关了）。接了就把原文交给脚本，由它决定怎么说
                    if (cbError == null) sink.fail("WS", msg);
                    else post(cbError, new Object[] { msg });
                }

                @Override public void onClosed(String info) {
                    post(cbClose, new Object[] { info });
                }
            });
            client.connect();
        }

        private void post(Function f, Object[] args) {
            if (f == null) return;
            handler.post(() -> callFn(f, args));
        }

        private BaseFunction fn0(Runnable r) {
            return new BaseFunction() {
                @Override public Object call(Context cx, Scriptable sc, Scriptable t, Object[] a) {
                    r.run();
                    return null;
                }
            };
        }

        private BaseFunction fn1(java.util.function.Consumer<Object> c) {
            return new BaseFunction() {
                @Override public Object call(Context cx, Scriptable sc, Scriptable t, Object[] a) {
                    c.accept(a.length > 0 ? a[0] : null);
                    return null;
                }
            };
        }

        private String str(Object o) {
            return o == null || o == Undefined.instance ? "" : String.valueOf(o);
        }

        private Function asFn(Object o) {
            return o instanceof Function ? (Function) o : null;
        }
    }

    /** 给 JS 的音频帧：**不把 Java 数组交给脚本**，只暴露两个方法（见类注释 ③）。 */
    private static final class Frame extends ScriptableObject {
        private final byte[] buf;
        private final int len;

        Frame(byte[] buf, int len) {
            this.buf = buf;
            this.len = len;
        }

        @Override
        public String getClassName() {
            return "Frame";
        }

        @Override
        public Object get(String name, Scriptable start) {
            if ("length".equals(name)) return len;
            if ("b64".equals(name)) {
                return new BaseFunction() {
                    @Override
                    public Object call(Context cx, Scriptable sc, Scriptable thisObj,
                            Object[] args) {
                        return Base64.encodeToString(buf, 0, len, Base64.NO_WRAP);
                    }
                };
            }
            return super.get(name, start);
        }
    }

    // ------------------------------------------------------------------ 工具

    private interface Body {
        Object call(Object[] args) throws Throwable;
    }

    private void fn(Context cx, Scriptable target, String name, Body body) {
        target.put(name, target, new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable sc, Scriptable thisObj, Object[] args) {
                try {
                    return body.call(args);
                } catch (Throwable tr) {
                    throw Context.throwAsScriptRuntimeEx(tr);
                }
            }
        });
    }

    private static void put(Context cx, Scriptable target, String name, Object v) {
        target.put(name, target, v);
    }

    private static void stripDangerous(Scriptable scope) {
        for (String n : new String[] { "Packages", "java", "javax", "org", "com", "edu", "net",
                "JavaAdapter", "getClass", "loadClass", "importClass", "importPackage",
                "load", "readFile", "readUrl", "spawn", "runCommand", "quit", "sync",
                "print", "environment", "help" }) {
            try {
                scope.delete(n);
            } catch (Throwable ignored) {
            }
        }
    }

    /** JS 里攒的帧数组 → 连续 PCM（数组元素是宿主给的 {@code Frame}）。 */
    private byte[] concatFrames(Object o) {
        if (!(o instanceof Scriptable)) return new byte[0];
        final Scriptable arr = (Scriptable) o;
        final int n = (int) num(new Object[]{arr.get("length", arr)}, 0);
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            final Object it = arr.get(i, arr);
            if (it instanceof Frame) {
                final Frame f = (Frame) it;
                bos.write(f.buf, 0, f.len);
            }
        }
        return bos.toByteArray();
    }

    private static String args(Object[] a, int i) {
        if (a == null || i >= a.length || a[i] == null || a[i] == Undefined.instance) return "";
        return String.valueOf(a[i]);
    }

    private static double num(Object[] a, int i) {
        if (a == null || i >= a.length || !(a[i] instanceof Number)) return 0;
        return ((Number) a[i]).doubleValue();
    }

    private static String str(Object o) {
        return o == null || o == Undefined.instance ? "" : String.valueOf(o);
    }

    /** JS 值 → byte[]（String 走 UTF-8；Frame 走它自己的字节）。 */
    private static byte[] bytes(Object[] a, int i) {
        if (a == null || i >= a.length || a[i] == null) return new byte[0];
        final Object o = a[i];
        if (o instanceof Frame) return ((Frame) o).buf;
        if (o instanceof String) return ((String) o).getBytes(StandardCharsets.UTF_8);
        return String.valueOf(o).getBytes(StandardCharsets.UTF_8);
    }

    private static String mac(String alg, String key, byte[] data) throws Exception {
        final javax.crypto.Mac m = javax.crypto.Mac.getInstance(alg);
        m.init(new javax.crypto.spec.SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), alg));
        return Base64.encodeToString(m.doFinal(data), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------ 沙箱

    private static final class Sandbox extends ContextFactory {
        volatile long deadlineMs;

        @Override
        protected Context makeContext() {
            final Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);          // 见类注释 ①
            cx.setInstructionObserverThreshold(INSTRUCTION_BUDGET);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int count) {
            final long d = deadlineMs;
            if (d > 0 && System.currentTimeMillis() > d) {
                throw new Error("script-timeout");   // 被 call() 捕获 ⇒ fail()
            }
        }

        @Override
        protected void onContextCreated(Context cx) {
            super.onContextCreated(cx);
            // 注意：setClassShutter / setWrapFactory 在 **Context** 上（不是 ContextFactory），
            // 且必须在上下文被使用之前设 —— onContextCreated 正是这个时机。
            cx.setClassShutter(name -> false);       // ① 任何 Java 类都不给
            cx.setWrapFactory(new WrapFactory() {    // ② 任何 Java 对象都不给
                @Override
                public Object wrap(Context c, Scriptable scope, Object obj, Class<?> t) {
                    if (obj == null || obj instanceof String || obj instanceof Number
                            || obj instanceof Boolean || obj instanceof Scriptable) {
                        return obj;
                    }
                    return null;
                }
            });
        }
    }
}
