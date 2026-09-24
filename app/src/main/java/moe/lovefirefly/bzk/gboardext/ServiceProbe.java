package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

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

    /** 留着给 DexKit 探针用：{@code getModuleApplicationInfo()} 能拿到我们模块 APK 的路径。 */
    private static volatile XposedModule sModule;
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
        sModule = module;
        final Class<?> svc;
        try {
            svc = Class.forName("android.inputmethodservice.InputMethodService", false, cl);
        } catch (Throwable tr) {
            warnOnce("InputMethodService not found: " + tr);
            sInstalled = true;
            return;
        }
        hookInputView(module, svc, "onCreateInputView");
        hookInputView(module, svc, "setInputView");
        hook(module, svc, "onStartInput", EditorInfo.class, boolean.class);
        hook(module, svc, "onStartInputView", EditorInfo.class, boolean.class);
        hook(module, svc, "onCurrentInputMethodSubtypeChanged", InputMethodSubtype.class);
        hook(module, svc, "onKeyDown", int.class, KeyEvent.class);
        // 抬起这条框架类上没被覆盖，先用它确认"物理键确实会到 IME"。
        // 真正的 onKeyDown（Gboard 自己覆盖了）由 EnterFix 挂到实例的类上，见其 installKeys。
        hook(module, svc, "onKeyUp", int.class, KeyEvent.class);
        // Gboard 自己切语言时走这条（public API），参数里的 subtype 就是目标语言。
        // 严格模式会拦掉这个调用，但**拦掉之前我们照样能从参数学到语言** ——
        // 这条是"框架不知道 Gboard 内部语言"时的唯一可靠来源（实测 ja_JP）。
        hook(module, svc, "switchInputMethod", String.class, InputMethodSubtype.class);
        // 选区变化（点击/拖动改光标、程序 setSelection 都会来）：
        // closeSkip 靠它判断"用户是不是把光标点到别处了" ⇒ 是的话上一次补全作废。
        // 注意它带 6 个 int，不能用上面的 hook() 助手（那个只转发参数、不管返回值语义）。
        hookSelectionUpdate(module, svc);
        SymbolNormHook.install(module, cl);      // 符号归一（中文态）
        installKeyProbe(module, cl, svc);        // 诊断：软键盘按键的 KeyEvent
        sInstalled = true;
        Log.i(TAG, "service probe installed on " + svc.getName());
    }

    /**
     * 输入视图那两条单独挂：只为把 View 塞给 {@link Banner}（热键提示要有个落脚点）。
     *
     * <p>{@code setInputView(View)} 有参数、{@code onCreateInputView()} 没有，所以要分两种签名。
     */
    private static void hookInputView(XposedModule module, Class<?> svc, String name) {
        try {
            final Method m = name.equals("setInputView")
                    ? svc.getDeclaredMethod(name, android.view.View.class)
                    : svc.getDeclaredMethod(name);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object r = chain.proceed();
                if (name.equals("setInputView") && chain.getArg(0) instanceof android.view.View) {
                    Banner.attachView((android.view.View) chain.getArg(0));
                } else if (r instanceof android.view.View) {
                    Banner.attachView((android.view.View) r);
                }
                return r;
            });
        } catch (Throwable tr) {
            Log.w(TAG, "input view hook " + name + " failed: " + tr);
        }
    }

    /**
     * 实例类链上是否已经挂到 {@code onUpdateSelection}。
     *
     * <p>挂到之后，框架类那份就只放行、不再回调 —— 见 {@link #hookSelectionUpdateOnImpl}。
     */
    private static volatile boolean sSelOnImpl;

    /**
     * 挂 {@code onUpdateSelection(int×6)} 并转给 {@link AutoPair#onSelectionChanged}。
     *
     * <p>新选区是第 3、4 个参数（oldSelStart/End/newSelStart/End/composingStart/End）。
     */
    private static void hookSelectionUpdate(XposedModule module, Class<?> svc) {
        try {
            final Method m = svc.getDeclaredMethod("onUpdateSelection", int.class, int.class,
                    int.class, int.class, int.class, int.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                // 实例类链上已经挂到了 ⇒ 这条框架实现根本不会被调到（覆盖版不调 super），
                // 万一某机型调了 super，也在这里挡住，免得同一次选区变化回调两遍。
                if (sSelOnImpl) return chain.proceed();
                try {
                    final Object a2 = chain.getArg(2);
                    final Object a3 = chain.getArg(3);
                    if (a2 instanceof Integer && a3 instanceof Integer) {
                        AutoPair.onSelectionChanged((Integer) a2, (Integer) a3);
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "selection hook err: " + tr);
                }
                return chain.proceed();
            });
        } catch (Throwable tr) {
            Log.w(TAG, "selection hook not installed: " + tr);
        }
    }

    /**
     * 沿<b>实例自己的类链</b>挂 {@code onUpdateSelection}（与 {@link KeyRouter} 挂按键同一套办法）。
     *
     * <p>为什么不能只挂框架类：Xposed 挂的是<b>方法</b>而不是虚分派。框架那边是
     * {@code InputMethodService$InputMethodSessionImpl.updateSelection()} 里的
     * {@code invoke-virtual → InputMethodService.onUpdateSelection}，分派到 Gboard 覆盖的
     * {@code ozc.onUpdateSelection}；而 Gboard 的覆盖版<b>不调 super</b> ⇒ 框架实现永不执行
     * ⇒ 挂在框架上的那条钩子从来不响（实测：17.2.2 的 {@code nix}、18.3.1 的 {@code ozc} 都不调）。
     * 后果是 closeSkip 的"手动移光标 ⇒ 上次补全作废"失效。
     *
     * <p>挂到 ≥1 个就把 {@link #sSelOnImpl} 置上，框架那份退化成放行。
     */
    private static void hookSelectionUpdateOnImpl(XposedModule module, Class<?> implClass) {
        if (implClass == null || sSelOnImpl) return;
        int n = 0;
        for (Class<?> c = implClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("android.inputmethodservice.InputMethodService")) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!"onUpdateSelection".equals(m.getName())) continue;
                final Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 6) continue;
                boolean allInt = true;
                for (Class<?> p : ps) if (p != int.class) allInt = false;
                if (!allInt) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        try {
                            final Object a2 = chain.getArg(2);
                            final Object a3 = chain.getArg(3);
                            if (a2 instanceof Integer && a3 instanceof Integer) {
                                AutoPair.onSelectionChanged((Integer) a2, (Integer) a3);
                            }
                        } catch (Throwable tr) {
                            Log.w(TAG, "selection hook err: " + tr);
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "selection: hooked "
                            + m.getDeclaringClass().getSimpleName() + "#onUpdateSelection");
                    n++;
                } catch (Throwable tr) {
                    Log.w(TAG, "selection: hook " + m.getName() + " failed: " + tr);
                }
            }
        }
        if (n > 0) {
            sSelOnImpl = true;      // 先挂完再置位：挂的过程中框架那份还能兜着
            Log.i(TAG, "selection: installed on " + implClass.getName() + " (" + n + ")");
        } else {
            Log.w(TAG, "selection: no onUpdateSelection on impl chain, keep framework hook");
        }
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
                        if (a instanceof EditorInfo) {
                            // "这颗 Enter 是谁的输入框" —— QQ 那类 App 自己吃键时靠它区分。
                            // 放在门控外：不打印也要维护，诊断才随时可用。
                            final EditorInfo ei = (EditorInfo) a;
                            EnterFix.setEditorPkg(ei.packageName);
                            // 当前编辑器类型：物理键全角化只在"文本类"里做（数字/电话类输入框
                            // 期待的是 ASCII 数字，转全角会把校验搞坏）
                            sEditorInputType = ei.inputType;
                        }
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
                            final KeyEvent ke = (KeyEvent) a;
                            sb.append(" key=").append(ke.getKeyCode())
                              .append(" ").append(KeyEvent.keyCodeToString(ke.getKeyCode()))
                              .append(" action=").append(ke.getAction())
                              .append(" meta=0x").append(Integer.toHexString(ke.getMetaState()))
                              .append(" repeat=").append(ke.getRepeatCount());
                        } else if (a instanceof EditorInfo) {
                            final EditorInfo ei = (EditorInfo) a;
                            sb.append(" editor=0x").append(Integer.toHexString(ei.inputType))
                              .append(" imeOptions=0x").append(Integer.toHexString(ei.imeOptions))
                              .append(" actionId=").append(ei.actionId)
                              .append(" pkg=").append(ei.packageName);
                        } else if (a instanceof Integer) {
                            sb.append(" arg=").append(a);
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
                    GboardState.attach(c);      // 状态位（全角/中英文标点）落在这个 Context 的 prefs
                    // 起来就把当前三个状态位回传给设置页一次：只靠"热键变化"会漏 ——
                    // 键盘没跑时按不了热键，而设置页进来时模块可能还没启动（那次请求就丢了）
                    GboardState.mirrorNow();
                    refreshLangAsync(c);        // 拿"当前语言"（公开 API）
                    final android.content.Context c2 = c;
                    // 必须用"服务实例"的 loader（LatinIME → Gboard 的 app loader）：
                    // svc 是框架类 InputMethodService，它的 loader 是 boot，看不见 Gboard 的类 ✗
                    final ClassLoader scl = chain.getThisObject().getClass().getClassLoader();
                    final Thread dt = new Thread(() -> probeDexKit(c2, scl), "bzk-dexkit");
                    dt.setDaemon(true);
                    dt.start();
                    // 语音转文字「回传契约」探针（只读，见 VoiceProbe）：语音在同一个 Gboard
                    // 进程里但是另一条完全独立的代码路径，和标点管线无关，所以单独起线程装。
                    final Thread vt = new Thread(() -> VoiceProbe.install(sModule, scl, c2),
                            "bzk-voice");
                    vt.setDaemon(true);
                    vt.start();
                    // 语音引擎宿主（P0，见 local/VOICE-ENGINE-INTERFACE.md）：
                    // 引擎 id 为空时它只装一个"透传"的钩子（不接管），配了才换成我们的引擎。
                    final Thread et = new Thread(
                            () -> VoiceEngineHost.install(sModule, scl, c2), "bzk-voice-host");
                    et.setDaemon(true);
                    et.start();
                    // 离线语音落地前的存储探测（开发期开关，见 StorageProbe）：在 Gboard 进程里
                    // 回答"权重能放哪、能不能跨 App 读"。
                    StorageProbe.run(c2);
                }
                // 顺手把当前的输入连接挂上（严格模式要靠它拦注入的按键）
                try {
                    if (chain.getThisObject() instanceof android.inputmethodservice.InputMethodService) {
                        final android.view.inputmethod.InputConnection cur =
                                ((android.inputmethodservice.InputMethodService) chain.getThisObject())
                                        .getCurrentInputConnection();
                        KeyGuard.installConnection(module, cur);
                        // 中文态 Enter（配置项 enterCommitPinyin，运行期判定）
                        EnterFix.installConnection(module, cur);
                        // 物理按键统一挂点：热键 + Enter（挂在实例自己的类链上）
                        KeyRouter.install(module, chain.getThisObject().getClass());
                        // 选区变化（closeSkip 作废判据）也挂在实例类链上 —— 框架那条被 Gboard
                        // 自己的覆盖版挡掉了，挂框架等于不响（见 hookSelectionUpdateOnImpl）
                        hookSelectionUpdateOnImpl(module, chain.getThisObject().getClass());
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
    /** 当前编辑器的 inputType（0 = 未知，按文本类处理）。 */
    private static volatile int sEditorInputType;

    static int editorInputType() {
        return sEditorInputType;
    }

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

    /**
     * 诊断用：软键盘的按键会不会走 KeyEvent。
     *
     * <p>这决定顿号映射能不能区分"按了反斜杠键"和"从符号页点了顿号"——
     * 两者提交的字符完全一样，只能靠物理键信息分开。
     */
    private static void installKeyProbe(XposedModule module, ClassLoader cl, Class<?> svc) {
        if (!BridgeHook.DEV_INPUT_TRACE) return;
        try {
            final Class<?> ric = Class.forName(
                    "android.inputmethodservice.RemoteInputConnection", false, cl);
            final Method m = ric.getDeclaredMethod("sendKeyEvent", KeyEvent.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a = chain.getArg(0);
                if (a instanceof KeyEvent) {
                    final KeyEvent ke = (KeyEvent) a;
                    Log.i(TAG, "probe keyEvent code=" + ke.getKeyCode()
                            + " unicode=" + (int) ke.getUnicodeChar());
                }
                return chain.proceed();
            });
            Log.i(TAG, "probe: sendKeyEvent hooked");
        } catch (Throwable tr) {
            Log.w(TAG, "probe: sendKeyEvent failed: " + tr);
        }
        try {
            final Method m = svc.getDeclaredMethod("sendKeyChar", char.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                Log.i(TAG, "probe sendKeyChar arg=" + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, "probe: sendKeyChar hooked");
        } catch (Throwable tr) {
            Log.w(TAG, "probe: sendKeyChar failed: " + tr);
        }
        try {
            final Method m = svc.getDeclaredMethod("sendDownUpKeyEvents", int.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                Log.i(TAG, "probe sendDownUpKeyEvents arg=" + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, "probe: sendDownUpKeyEvents hooked");
        } catch (Throwable tr) {
            Log.w(TAG, "probe: sendDownUpKeyEvents failed: " + tr);
        }
    }

    /**
     * 诊断：用 DexKit 在目标进程里按<b>结构</b>找"决定按键出什么"的代码。
     *
     * <p>为什么不能用名字：Gboard 自身类名/方法名是混淆的（实测 {@code onCodeInput} 之类
     * 命中 0）。经典键盘回调形状（{@code (int,int[])void} + {@code (CharSequence)void}）
     * 在这版 Gboard 里也不存在 —— 所以改用<b>字符串引用</b>这个结构信号：
     * 谁的代码里出现 {@code 、}(U+3001) / {@code ／}(U+FF0F)，谁就是按键输出的定义处。
     */
    private static void probeDexKit(android.content.Context ctx, ClassLoader cl) {
        if (!BridgeHook.DEV_INPUT_TRACE) return;
        try {
            final String apk = ctx.getPackageManager()
                    .getApplicationInfo(BridgeHook.TARGET_PKG, 0).sourceDir;
            Log.i(TAG, "dexkit: apk = " + apk);
            if (!loadDexKitNative(ctx)) return;
            final DexKitBridge bridge = DexKitBridge.create(apk);
            Log.i(TAG, "dexkit: dexNum = " + bridge.getDexNum());
            for (String s : new String[]{"\u3001", "\uFF0F"}) {
                final ClassDataList cs = bridge.findClass(
                        FindClass.create().matcher(ClassMatcher.create().usingStrings(s)));
                Log.i(TAG, "dexkit: class usingStrings(" + s + ") = " + cs.size());
                int n = 0;
                for (ClassData c : cs) {
                    if (n++ >= 10) break;
                    Log.i(TAG, "    C " + c.getName());
                }
                final MethodDataList ms = bridge.findMethod(
                        FindMethod.create().matcher(MethodMatcher.create().usingStrings(s)));
                Log.i(TAG, "dexkit: method usingStrings(" + s + ") = " + ms.size());
                n = 0;
                for (MethodData m : ms) {
                    if (n++ >= 10) break;
                    Log.i(TAG, "    M " + m.getClassName() + "->" + m.getName()
                            + m.getDescriptor());
                }
            }
            probeCommitDispatch(bridge, cl);
            bridge.close();
        } catch (Throwable tr) {
            Log.w(TAG, "dexkit probe failed: " + tr);
        }
    }

    /**
     * 诊断：三条提交路径（按键 / 符号页 / 候选）在提交那一刻，能不能用某个字段区分。
     *
     * <p>思路：提交 lambda（{@code Lmn.run} / {@code Lmza.run}）是按字段 {@code d} 分发几十个
     * lambda 体的，所以同一时刻这个字段的值可能就代表"哪条路径"。整条链都用 DexKit
     * <b>按结构</b>找，不写死混淆名：
     * <ol>
     *   <li>谁直接调框架 {@code InputConnection.commitText}（= 提交漏斗）；</li>
     *   <li>谁调这个漏斗（= 提交 lambda）；</li>
     *   <li>hook 它们，把 {@code this} 的所有 int 字段打出来。</li>
     * </ol>
     */
    /**
     * 把 DexKit 给的"类名 + 完整签名"解析成 {@code java.lang.reflect.Method}。
     *
     * <p>为什么不用 {@code MethodData.getMethodInstance(cl)}：它对<b>默认包（无包名）混淆类</b>
     * 会 {@code ClassNotFoundException: nec} ✗（实测）。这里自己拆签名，顺便把
     * {@code L...;} 与基本类型都映射好。
     */
    private static java.lang.reflect.Method resolve(ClassLoader cl, String clsName, String sign) {
        try {
            String cn = clsName;
            if (cn.startsWith("L") && cn.endsWith(";")) cn = cn.substring(1, cn.length() - 1);
            cn = cn.replace('/', '.');
            final int lp = sign.indexOf('(');
            final int rp = sign.indexOf(')');
            if (lp < 0 || rp < 0) return null;
            final String pd = sign.substring(lp + 1, rp);
            final java.util.List<Class<?>> ps = new java.util.ArrayList<>();
            for (int i = 0; i < pd.length(); ) {
                final char ch = pd.charAt(i);
                if (ch == 'L') {
                    final int e = pd.indexOf(';', i);
                    ps.add(Class.forName(pd.substring(i + 1, e).replace('/', '.'), false, cl));
                    i = e + 1;
                } else {
                    ps.add(prim(ch));
                    i++;
                }
            }
            final String name = sign.substring(sign.indexOf("->") + 2, lp);
            return Class.forName(cn, false, cl)
                    .getDeclaredMethod(name, ps.toArray(new Class<?>[0]));
        } catch (Throwable tr) {
            Log.w(TAG, "resolve failed " + clsName + sign + ": " + tr);
            return null;
        }
    }

    private static Class<?> prim(char c) {
        switch (c) {
            case 'I': return int.class;
            case 'Z': return boolean.class;
            case 'F': return float.class;
            case 'J': return long.class;
            case 'D': return double.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            default: return void.class;
        }
    }

    private static void probeCommitDispatch(DexKitBridge bridge, ClassLoader cl) {
        try {
            final MethodDataList funnels = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addInvoke(
                            "Landroid/view/inputmethod/InputConnection;->commitText"
                                    + "(Ljava/lang/CharSequence;I)Z")));
            Log.i(TAG, "dexkit: commit funnel = " + funnels.size());
            for (MethodData f : funnels) {
                final String sig = f.getDescriptor();       // DexKit 给的就是完整签名
                Log.i(TAG, "      funnel " + sig);
                try {
                    final java.lang.reflect.Method m = resolve(cl, f.getClassName(),
                            f.getDescriptor());
                    if (m == null) {
                        Log.w(TAG, "      no Method instance: " + sig);
                        continue;
                    }
                    m.setAccessible(true);
                    sModule.hook(m).intercept(chain -> {
                        final Object self = chain.getThisObject();
                        final StringBuilder sb = new StringBuilder("probe funnel ").append(sig);
                        if (self != null) {
                            // 提交 lambda：分发型字段就在这里
                            sb.append(" this=").append(self.getClass().getName()).append(" ints=");
                            try {
                                for (java.lang.reflect.Field fd
                                        : self.getClass().getDeclaredFields()) {
                                    fd.setAccessible(true);
                                    if (fd.getType() == int.class) {
                                        sb.append(fd.getName()).append('=')
                                          .append(fd.getInt(self)).append(' ');
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        } else {
                            // 静态漏斗（Lnei.f）：第二个参数就是提交文本
                            sb.append(" args=");
                            try {
                                for (Object a : chain.getArgs()) {
                                    if (a instanceof CharSequence) {
                                        sb.append('"').append(a).append("\" ");
                                    } else {
                                        sb.append(a == null ? "null "
                                                : a.getClass().getSimpleName()).append(' ');
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        Log.i(TAG, sb.toString());
                        return chain.proceed();
                    });
                    Log.i(TAG, "      hooked " + sig);
                } catch (Throwable tr) {
                    Log.w(TAG, "      hook failed " + sig + ": " + tr);
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "probeCommitDispatch failed: " + tr);
        }
    }

    /**
     * 手动加载 DexKit 的 native 库。
     *
     * <p>模块跑在 <b>Gboard 进程</b>里，系统不会把我们模块 APK 的 lib 目录加进 Gboard 的
     * library path ⇒ {@code DexKitBridge.create()} 直接 {@code UnsatisfiedLinkError}
     * （实测）。所以自己来：从 classloader 反推模块 APK 路径 → 按当前 ABI 取出
     * {@code lib/<abi>/libdexkit.so} → 落到宿主 App 的 cache 目录（我们是它的 uid，能写）
     * → {@code System.load()} 绝对路径。
     */
    static boolean loadDexKitNative(android.content.Context ctx) {
        try {
            String apk = null;
            // 正路：libxposed 的 getModuleApplicationInfo() 直接给模块 APK 路径
            try {
                final XposedModule m = sModule;
                if (m != null && m.getModuleApplicationInfo() != null) {
                    apk = m.getModuleApplicationInfo().sourceDir;
                }
            } catch (Throwable ignored) {
            }
            // 兜底：classloader 的 CodeSource（LSPosed 下通常是 null）
            if (apk == null) {
                try {
                    final java.security.CodeSource cs =
                            ServiceProbe.class.getProtectionDomain().getCodeSource();
                    if (cs != null && cs.getLocation() != null) apk = cs.getLocation().getPath();
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "dexkit: module apk = " + apk);
            if (apk == null) return false;

            String abi = null;
            for (String a : android.os.Build.SUPPORTED_ABIS) {
                if (a.startsWith("arm64")) { abi = "arm64-v8a"; break; }
                if (a.startsWith("armeabi")) { abi = "armeabi-v7a"; break; }
                if (a.startsWith("x86_64")) { abi = "x86_64"; break; }
                if (a.startsWith("x86")) { abi = "x86"; break; }
            }
            if (abi == null) abi = android.os.Build.SUPPORTED_ABIS[0];

            final java.io.File out = new java.io.File(ctx.getCacheDir(), "libdexkit.so");
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                final java.util.zip.ZipEntry e = zf.getEntry("lib/" + abi + "/libdexkit.so");
                if (e == null) {
                    Log.w(TAG, "dexkit: no libdexkit.so for " + abi);
                    return false;
                }
                try (java.io.InputStream in = zf.getInputStream(e);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            System.load(out.getAbsolutePath());
            Log.i(TAG, "dexkit: native loaded, " + out.length() + " bytes from " + abi);
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "dexkit: load native failed: " + tr);
            return false;
        }
    }

    private static void warnOnce(String msg) {
        if (sWarned) return;
        sWarned = true;
        Log.w(TAG, msg);
    }
}
