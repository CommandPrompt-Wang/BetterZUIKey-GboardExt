package moe.lovefirefly.bzk.gboardext;

import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
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

        void log(String msg);
    }

    private final Sandbox factory = new Sandbox();
    private final Sink sink;
    private final Handler handler;

    private Scriptable scope;
    private Scriptable engineObj;
    private volatile boolean loaded;

    ScriptEngine(Sink sink, Handler handler) {
        this.sink = sink;
        this.handler = handler;
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
                ScriptableObject.putProperty(scope, "engine", cx.newObject(scope));
                ScriptableObject.putProperty(scope, "ctx", buildCtx(cx, config,
                        languageTag, sampleRate, frameBytes));
                cx.evaluateString(scope, script, name, 1, null);
                final Object e = scope.get("engine", scope);
                if (!(e instanceof Scriptable)) {
                    sink.fail("SCRIPT", "脚本没有定义 engine 对象");
                    return false;
                }
                engineObj = (Scriptable) e;
                loaded = true;
                Log.i(TAG, "script loaded: " + name + " id=" + str(engineObj.get("id", engineObj)));
                return true;
            } finally {
                Context.exit();
            }
        } catch (Throwable tr) {
            sink.fail("SCRIPT", "脚本加载失败: " + tr);
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
            sink.fail("SCRIPT", fn + " 异常: " + tr.getMessage());
        }
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
        fn(cx, ctx, "after", a -> {
            final Object f = a.length > 1 ? a[1] : null;
            final long ms = (long) num(a, 0);
            if (f instanceof Function) {
                // 投递回同一个 Handler 线程（Rhino Context 不跨线程）
                handler.postDelayed(() -> callFn((Function) f), ms);
            }
            return null;
        });
        // P2 才实现：WebSocket 客户端（iflytek/volc 用）
        fn(cx, ctx, "ws", a -> {
            throw new IllegalStateException("P0 未实现 WebSocket（iflytek 那一版补）");
        });
        return ctx;
    }

    private void callFn(Function f) {
        if (!loaded || f == null) return;
        try {
            final Context cx = factory.enterContext();
            try {
                factory.deadlineMs = System.currentTimeMillis() + 500;
                f.call(cx, scope, engineObj, new Object[0]);
            } finally {
                factory.deadlineMs = 0;
                Context.exit();
            }
        } catch (Throwable tr) {
            sink.fail("SCRIPT", "定时回调异常: " + tr.getMessage());
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
