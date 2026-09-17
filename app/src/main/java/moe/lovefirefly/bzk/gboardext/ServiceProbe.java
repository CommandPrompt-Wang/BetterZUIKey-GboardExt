package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 第一轮的只读探针：挂 {@code android.inputmethodservice.InputMethodService} 的框架方法。
 *
 * <p>为什么挂框架方法而不是 Gboard 自己的类：框架类/方法名不被混淆，换版本也不会变 ——
 * 这正是"不硬编码混淆名"的第一步。运行时用 {@code getThisObject().getClass()} 就能拿到
 * Gboard 真正实现的那个类（静态分析里是 {@code nix}，但代码里不出现它）。
 */
final class ServiceProbe {

    private static final String TAG = "GboardExt";
    private static volatile boolean sInstalled;
    private static volatile boolean sWarned;
    private static volatile boolean sWatchStarted;

    /**
     * 当前 subtype 的语言串（{@code zh_CN} / {@code ja} / {@code en_US}）。
     *
     * <p><b>空 = 认不出来 = 不归一</b>（fail-safe）：Gboard 传进来的 subtype 常常不带
     * locale（实测日志里是 {@code subtype=/keyboard}），这时宁可什么都不做，也不能把
     * 日语/英语当中文改了。
     */
    private static volatile String sLang = "";

    /** 最近一次看到的 subtype hash，以及"hash → 语言"兜底表（安装时从框架的已启用列表建）。 */
    private static volatile int sHash;

    /**
     * 上一次从<b>框架</b>读到的 subtype hash（-1 = 还没读过）。
     *
     * <p>用来识别"框架给的是老消息"：Gboard 自己切语言时那条 {@code switchInputMethod}
     * 被 strict 拦下 ⇒ 框架<b>不知道</b>语言变了，之后每次问它都还是旧的那个。
     * 这种"没变化"的重复值不许覆盖我们从切换请求里学到的语言。
     */
    private static volatile int sFwHash = -1;
    private static final java.util.Map<Integer, String> sHashLang =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 见过的 subtype hash（诊断去重）+ 上次刷新语言的时间（会话开始会连着来几条）。 */
    private static final java.util.Set<Integer> sSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile long sLastLangRefresh;

    private ServiceProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        final Class<?> svc;
        try {
            svc = Class.forName("android.inputmethodservice.InputMethodService", false, cl);
        } catch (Throwable tr) {
            warnOnce("InputMethodService not found: " + tr);
            sInstalled = true;
            return;
        }
        hook(module, svc, "onCreateInputView");
        hook(module, svc, "setInputView", android.view.View.class);
        hook(module, svc, "onStartInput", EditorInfo.class, boolean.class);
        hook(module, svc, "onStartInputView", EditorInfo.class, boolean.class);
        hook(module, svc, "onCurrentInputMethodSubtypeChanged", InputMethodSubtype.class);
        hook(module, svc, "onKeyDown", int.class, KeyEvent.class);
        // Gboard 自己切语言时走这条（public API），参数里的 subtype 就是目标语言。
        // 严格模式会拦掉这个调用，但**拦掉之前我们照样能从参数学到语言** ——
        // 这条是"框架不知道 Gboard 内部语言"时的唯一可靠来源（实测 ja_JP）。
        hook(module, svc, "switchInputMethod", String.class, InputMethodSubtype.class);
        SymbolNormHook.install(module, cl);      // 符号归一（中文态）
        sInstalled = true;
        Log.i(TAG, "service probe installed on " + svc.getName());
    }

    private static void hook(XposedModule module, Class<?> svc, String name, Class<?>... params) {
        try {
            final Method m = svc.getDeclaredMethod(name, params);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                // 中文判据：记住当前 subtype 的语言。
                // onCurrentInputMethodSubtypeChanged 直接带 subtype 参数；会话开始的两条
                // 参数里没有，就从服务上读当前 subtype（进会话时框架已经设好了）。
                try {
                    InputMethodSubtype st = null;
                    for (Object a : chain.getArgs()) {
                        if (a instanceof InputMethodSubtype) {
                            st = (InputMethodSubtype) a;
                            break;
                        }
                    }
                    final Object self0 = chain.getThisObject();
                    if (st != null) {
                        learnLang(st);
                    } else if (name.startsWith("onStartInput")
                            && self0 instanceof android.content.Context) {
                        // 冷启动时框架不会主动告诉 IME 当前 subtype，得自己去问 IMM
                        refreshLangAsync((android.content.Context) self0);
                    }
                } catch (Throwable ignored) {
                }
                if (BridgeHook.DEV_SERVICE_TRACE) {
                    final Object self = chain.getThisObject();
                    final StringBuilder sb = new StringBuilder("svc ").append(name);
                    sb.append(" impl=").append(self == null ? "?" : self.getClass().getName());
                    for (Object a : chain.getArgs()) {
                        if (a instanceof InputMethodSubtype) {
                            final InputMethodSubtype st = (InputMethodSubtype) a;
                            sb.append(" subtype=").append(st.getLocale())
                              .append('/').append(st.getMode())
                              .append(" tag=").append(st.getLanguageTag())
                              .append(" hash=").append(Integer.toHexString(st.hashCode()));
                        } else if (a instanceof KeyEvent) {
                            sb.append(" key=").append(((KeyEvent) a).getKeyCode())
                              .append(" ").append(((KeyEvent) a).getAction());
                        } else if (a instanceof EditorInfo) {
                            sb.append(" editor=").append(((EditorInfo) a).inputType);
                        }
                    }
                    Log.i(TAG, sb.toString());
                }
                final Object r = chain.proceed();
                // 配置轮询必须用"输入法服务自己"的 Context：
                // systemContext() 自报包名是 android，provider 会以
                // "Given calling package android does not match caller's uid" 拒绝（踩过）
                if (!sWatchStarted && chain.getThisObject() instanceof android.content.Context) {
                    sWatchStarted = true;
                    final android.content.Context c = (android.content.Context) chain.getThisObject();
                    ConfigWatch.start(c);       // provider 通道（Gboard 上走不通，默认关）
                    BroadcastConfig.start(c);   // 广播通道（走这条）
                    refreshLangAsync(c);        // 拿"当前语言"（公开 API）
                }
                // 顺手把当前的输入连接挂上（严格模式要靠它拦注入的按键）
                try {
                    if (chain.getThisObject() instanceof android.inputmethodservice.InputMethodService) {
                        KeyGuard.installConnection(module,
                                ((android.inputmethodservice.InputMethodService) chain.getThisObject())
                                        .getCurrentInputConnection());
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
        } catch (Throwable tr) {
            Log.w(TAG, "hook " + name + " failed: " + tr);
        }
    }

    /**
     * 当前是不是中文态（符号归一的门控）。
     *
     * <p>认不出来（locale 为空 / 未知）时<b>不放行</b> —— 宁可不改，也不能把日语弄坏。
     */
    static boolean isChinese() {
        final String l = sLang;
        return l != null && l.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }

    /**
     * 反射读服务当前的 subtype。
     *
     * <p>{@code InputMethodService.getCurrentInputMethodSubtype()} 是 {@code @hide}，
     * 编译期看不到（直接调会报 cannot find symbol），但它在运行期是 public —— 反射可用。
     * 方法名属于框架、不被混淆，符合本模块"不硬编码混淆名"的原则。
     */
    /** subtype → 语言：先看它自己带的 locale/languageTag，再查 hash 表。 */
    private static String lookupLang(InputMethodSubtype st) {
        if (st == null) return "";
        String lang = langOf(st);
        if (lang.isEmpty()) lang = sHashLang.getOrDefault(st.hashCode(), "");
        return lang;
    }

    /** 记下这个 subtype 是什么语言；认不出来就保持现状（不清空）。 */
    private static void learnLang(InputMethodSubtype st) {
        if (st == null) return;
        sHash = st.hashCode();
        if (sSeen.add(sHash)) {
            Log.i(TAG, "subtype seen: hash=" + Integer.toHexString(sHash)
                    + " tag=" + st.getLanguageTag() + " locale=" + st.getLocale()
                    + " mode=" + st.getMode() + " extra=" + st.getExtraValue());
        }
        final String lang = lookupLang(st);
        if (lang.isEmpty()) {
            // 还是认不出来（例如 Gboard 那个本来就不带 locale 的默认 subtype）：
            // **保持现状**。之前这里清空过一次，结果是把中文一起关掉了 —— 认不出来 ≠ 不是中文，
            // 真正需要防的"日语被改"，靠的是 switchInputMethod 那条把 ja_JP 学到手。
            return;
        }
        applyLang(lang);
    }

    private static void applyLang(String lang) {
        final String v = lang == null ? "" : lang;
        if (v.equals(sLang)) return;
        sLang = v;
        Log.i(TAG, "subtype lang -> " + (v.isEmpty() ? "? 认不出来，停止归一" : v)
                + " (hash=" + Integer.toHexString(sHash) + ")");
    }

    /** 后台刷新"当前语言"：IMM 是公开 API，但走 binder，不在框架回调里同步等。 */
    private static void refreshLangAsync(android.content.Context ctx) {
        if (ctx == null) return;
        final long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastLangRefresh < 300) return;
        sLastLangRefresh = now;
        final Thread t = new Thread(() -> refreshLang(ctx), "bzk-lang");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 读"当前语言"。两个都是<b>公开 API</b>（{@code javap} 确认过），不需要反射：
     * <ul>
     *   <li>{@code InputMethodManager.getCurrentInputMethodSubtype()} 拿当前 subtype；</li>
     *   <li>{@code getEnabledInputMethodSubtypeList(info, true)} 建 hash → 语言 表 ——
     *       Gboard 自己传进 {@code onCurrentInputMethodSubtypeChanged} 的那个实例常常不带
     *       locale（实测 {@code subtype=/keyboard}），只能靠 hash 回查。</li>
     * </ul>
     * 实测对应：{@code zh_CN → 617035939}、{@code ja_JP → -1318396357}（与
     * {@code settings get secure enabled_input_methods} 里那两个 hash 一致）。
     */
    private static void refreshLang(android.content.Context ctx) {
        try {
            final android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                Log.w(TAG, "lang: IMM null");
                return;
            }
            if (sHashLang.isEmpty()) buildLangMap(imm, ctx.getPackageName());
            final InputMethodSubtype st = imm.getCurrentInputMethodSubtype();
            if (st == null) {
                Log.w(TAG, "lang: current subtype null");
                return;
            }
            final int h = st.hashCode();
            if (h == sFwHash) {
                // 框架值没变 = 老消息（原因见 sFwHash 的注释）。
                // 只有它真的变了，才说明发生了一次"框架驱动的切换"，那时才该采纳。
                final String fw = lookupLang(st);
                if (!fw.isEmpty() && !fw.equals(sLang)) {
                    Log.i(TAG, "lang: 框架仍说 " + fw + "（未变，保留 " + sLang + "）");
                }
                return;
            }
            sFwHash = h;
            learnLang(st);
        } catch (Throwable tr) {
            Log.w(TAG, "lang refresh failed: " + tr);
        }
    }

    /** hash → 语言：用 Gboard 自己那条 subtype 列表补齐（实测 147 条里 zh_CN / ja_JP 都在）。 */
    private static void buildLangMap(android.view.inputmethod.InputMethodManager imm, String pkg) {
        try {
            int n = 0;
            for (android.view.inputmethod.InputMethodInfo info : imm.getInputMethodList()) {
                if (!pkg.equals(info.getPackageName())) continue;
                for (InputMethodSubtype st : imm.getEnabledInputMethodSubtypeList(info, true)) {
                    final String lang = langOf(st);
                    if (lang.isEmpty()) continue;
                    sHashLang.put(st.hashCode(), lang);
                    n++;
                }
            }
            Log.i(TAG, "langmap: " + n + " entry(ies)");
        } catch (Throwable tr) {
            Log.w(TAG, "langmap failed: " + tr);
        }
    }

    /** languageTag 优先（API 24+），退回 locale。 */
    private static String langOf(InputMethodSubtype st) {
        String lang = st.getLanguageTag();
        if (lang == null || lang.isEmpty()) lang = st.getLocale();
        return lang == null ? "" : lang;
    }

    private static void warnOnce(String msg) {
        if (sWarned) return;
        sWarned = true;
        Log.w(TAG, msg);
    }
}
