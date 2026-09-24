package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 符号归一的挂点：IME 进程里的 {@code android.inputmethodservice.RemoteInputConnection}。
 *
 * <p><b>为什么挂它</b>：这是"标点真正上屏"的必经之路，而且它是<b>框架类</b>
 * （不被混淆、换 Gboard 版本也不会变）—— 于是整个功能不需要碰 Gboard 的任何内部类，
 * 也就没有硬编码混淆名的风险。
 *
 * <p>映射表硬编码在 {@link SymbolNorm}（不配置、不广播）。
 *
 * <p><b>只在中文态生效</b>：判据是当前 subtype 的 locale（{@link ServiceProbe#isChinese()}）。
 * 日语有完善的候选/组合框，一律不碰；英文态本来就是半角，改了也是空转。
 *
 * <p>组合态（{@code setComposingText}）也挂上：中文态下符号通常直接上屏，但拼音字母
 * 的组合态也会经过这里 —— 表里没有 ASCII 字母，所以那些字符天然不会被改。
 *
 * <p><b>全角化覆盖两条路</b>（用户口径：全角要包含字母数字 ＡＢＣ１２３）：
 * <ol>
 *   <li>{@code commitText}：整段 ASCII 都转（软键盘输入、以及部分 App 上的物理键都走这条）；</li>
 *   <li>{@code sendKeyEvent}：Gboard 把"自己没处理的物理键"转发给 App、由 App 自己插入字符的
 *       那条路（此时提交层看不到）—— 见 {@link #hookSendKeyEvent}。</li>
 * </ol>
 *
 * <p><b>必须挂"全部重载"</b>：Android 13+（API 33）给 {@code commitText} /
 * {@code setComposingText} 加了带 {@code TextAttribute} 的三参数版本，Gboard 在
 * 新系统上就走那条 —— 只挂两参数版本会漏掉大部分符号（实测：中文态只有第一个
 * {@code ｛} 被改，其余原样过去）。
 */
final class SymbolNormHook {

    private static final String TAG = "GboardExt";

    /** 开发期：打印每次被改写的提交对（验证期开过，已收敛）。 */
    static final boolean DEV_TRACE = false;

    /** 开发期诊断：把"含 ASCII 字母/数字"的每次提交都打出来（查全角化覆盖 + 数字走哪条路）。 */
    static final boolean DEV_TRACE_ALNUM = false;

    /**
     * 开发期诊断：把 {@code RemoteInputConnection} 上**所有会改文本的方法**都打出来
     * （commitText/setComposingText 之外的 finishComposingText、sendKeyEvent、replaceText…）。
     *
     * <p>用途：中文态的数字不走提交层（实测），要找出它到底从哪条路进编辑器。
     */
    static final boolean DEV_TRACE_ALL_IC = false;

    private static volatile boolean sInstalled;

    /** 智能编号（中文特供）：数字后面紧跟的 。/） 换成半角（1. 2) 这种）。 */
    private static volatile boolean sSmartNumber = true;

    /** 功能开关「智能中文标点」：关掉就跳过语义层（只留宽度层）。 */
    private static volatile boolean sSmartPunct = true;

    /** 功能开关「全角模式」：关掉 ⇒ 状态位被忽略，恒半角。 */
    private static volatile boolean sFullWidthFeature = true;

    /** 功能开关「中英文标点」：关掉 ⇒ 状态位被忽略，恒中文标点。 */
    private static volatile boolean sEnPunctFeature = true;

    /** 最近一次真正上屏的最后一个字符 —— 智能编号靠它判断"前面是不是数字"。 */
    private static volatile char sLast;

    /**
     * 问编辑器"光标前一个字符"。
     *
     * <p>为什么需要它：中文态打 `5）` 时钩子里可能只看到 `）`（数字没经过提交层），
     * 于是 `sLast` 还是空/`（` ✗ —— 所以"前一个是不是数字"只能向编辑器问。
     * 取不到时退回 {@link #sLast}。
     *
     * <p><b>后续实测修正（2026-09-24）</b>：数字走哪条路**取决于目标 App** ——
     * 有的 App 是 Gboard 把 KeyEvent 转发过去、由 App 自己插入（日志里只有
     * {@code key onKeyDown} + {@code IC* sendKeyEvent}），有的 App 走
     * {@code IC commitText "3"}。两条路现在都覆盖了（见 {@link #hookSendKeyEvent} 与宽度层），
     * 所以"数字总是绕过提交层"的说法不准确。
     */
    private static char beforeCursor(Object ic) {
        try {
            if (ic instanceof android.view.inputmethod.InputConnection) {
                final CharSequence cs = ((android.view.inputmethod.InputConnection) ic)
                        .getTextBeforeCursor(1, 0);
                if (cs != null && cs.length() > 0) return cs.charAt(cs.length() - 1);
            }
        } catch (Throwable ignored) {
        }
        return sLast;
    }

    static void setSmartPunct(boolean on) {
        if (sSmartPunct != on) Log.i(TAG, "smartPunct -> " + on);
        sSmartPunct = on;
    }

    static void setFullWidthFeature(boolean on) {
        if (sFullWidthFeature != on) Log.i(TAG, "fullwidth feature -> " + on);
        sFullWidthFeature = on;
    }

    static void setEnPunctFeature(boolean on) {
        if (sEnPunctFeature != on) Log.i(TAG, "enPunct feature -> " + on);
        sEnPunctFeature = on;
    }

    /** 物理补全的功能开关（由 BroadcastConfig 推过来；横幅文案要判断它）。 */
    private static volatile boolean sPhysCompleteFeature;

    static void setPhysCompleteFeature(boolean on) {
        if (sPhysCompleteFeature != on) Log.i(TAG, "physComplete feature -> " + on);
        sPhysCompleteFeature = on;
    }

    static boolean physCompleteFeature() {
        return sPhysCompleteFeature;
    }

    static boolean smartPunct() {
        return sSmartPunct;
    }

    static boolean fullWidthFeature() {
        return sFullWidthFeature;
    }

    static boolean enPunctFeature() {
        return sEnPunctFeature;
    }

    static void setSmartNumber(boolean on) {
        if (sSmartNumber != on) Log.i(TAG, "smartNumber -> " + on);
        sSmartNumber = on;
    }

    private SymbolNormHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        final Class<?> cls;
        try {
            cls = Class.forName("android.inputmethodservice.RemoteInputConnection", false, cl);
        } catch (Throwable tr) {
            Log.w(TAG, "norm: RemoteInputConnection not found: " + tr);
            return;
        }
        // 按名字枚举全部重载（2 参、3 参 TextAttribute…），只要求第一个参数是 CharSequence
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            final String nm = m.getName();
            if (!"commitText".equals(nm) && !"setComposingText".equals(nm)) continue;
            final Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 2 || !CharSequence.class.isAssignableFrom(ps[0])) continue;
            if (hook(module, m, nm)) n++;
        }
        if (hookSendKeyEvent(module, cls)) n++;
        if (DEV_TRACE_ALL_IC) n += hookMutators(module, cls);
        Log.i(TAG, "norm hooked " + n + " method(s) on " + cls.getSimpleName());
    }

    /**
     * 诊断：把这次提交的调用栈打出来。
     *
     * <p>目的：区分"按了反斜杠键"和"从符号页/候选点了顿号"——两者提交的字符完全一样，
     * 但**发起路径不同**，栈帧序列必然不同（按键走键盘触摸分发；符号页走面板/布局切换；
     * 候选走候选选择）。如果提交被 post 到 Handler 线程，栈里至少能看到是哪个 Runnable
     * （{@code Lmn.run} / {@code Lmza.run}）——那本身就是一条判据。
     */
    private static void logStack(String what) {
        try {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder sb = new StringBuilder("probe stack[").append(what)
                    .append("] thread=").append(Thread.currentThread().getName());
            int n = 0;
            for (StackTraceElement e : st) {
                final String cn = e.getClassName();
                if (cn.startsWith("java.lang.Thread") || cn.startsWith("dalvik.")
                        || cn.startsWith("android.os.Looper") || cn.startsWith("android.os.Handler")
                        || cn.startsWith("android.os.MessageQueue")) {
                    continue;                      // 跳过 VM / looper 噪音
                }
                sb.append("\n      ").append(cn).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber());
                if (++n >= 12) break;
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 已被我们"吞掉按键、自己上屏"的键 → 时间戳；它的抬起也要吞掉（避免 App 收到没有按下的抬起）。 */
    private static final java.util.Map<Integer, Long> sKeySwallowed =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 物理键盘全角化：{@code sendKeyEvent} 这条路上的可打印 ASCII 键，全角模式下改写成全角字符。
     *
     * <p><b>为什么必须单独处理</b>：物理键盘的数字/符号 Gboard **不自己插入**，而是把 KeyEvent
     * 转发给 App、由 App 插入字符（实测日志：{@code key onKeyDown kc=10} 之后紧跟
     * {@code IC* sendKeyEvent(...)}，但**没有**对应的 {@code IC commitText}）。
     * 提交层永远看不到它们 ⇒ 软键盘数字能全角、**物理键盘数字不能**（用户实测）。
     *
     * <p><b>做法</b>：按下时用 {@link KeyEvent#getUnicodeChar(int)} 问"这颗键本来会出什么字符"，
     * 是可打印 ASCII 就吞掉按键、改用 {@code commitText(全角)} 自己上屏；抬起也吞掉。
     * 两道保护：带 Ctrl/Alt/Meta 的组合一律不动（那是快捷键）；数字/电话类输入框不动
     * （它们期待 ASCII 数字，转全角会破坏校验）。
     */
    private static boolean hookSendKeyEvent(XposedModule module, Class<?> cls) {
        try {
            final Method m = cls.getDeclaredMethod("sendKeyEvent", android.view.KeyEvent.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a0 = chain.getArg(0);
                if (!(a0 instanceof android.view.KeyEvent)) return chain.proceed();
                final android.view.KeyEvent ke = (android.view.KeyEvent) a0;
                final int kc = ke.getKeyCode();
                final Long at = sKeySwallowed.remove(kc);
                final boolean ours = at != null
                        && android.os.SystemClock.uptimeMillis() - at < 1500;
                if (ke.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                    // 抬起：我们自己上过屏的那颗，抬起也吞掉（否则 App 收到"没有按下的抬起"）
                    return ours ? Boolean.TRUE : chain.proceed();
                }
                if (!(sFullWidthFeature && GboardState.fullwidth())) return chain.proceed();
                final int meta = ke.getMetaState();
                if ((meta & (android.view.KeyEvent.META_CTRL_ON
                        | android.view.KeyEvent.META_ALT_ON
                        | android.view.KeyEvent.META_META_ON)) != 0) return chain.proceed();
                final int type = ServiceProbe.editorInputType();
                final int klass = type & android.text.InputType.TYPE_MASK_CLASS;
                if (klass != 0 && klass != android.text.InputType.TYPE_CLASS_TEXT) {
                    return chain.proceed();          // 数字/电话/日期类：保持 ASCII
                }
                final int c = ke.getUnicodeChar(meta);
                if (c <= 0x20 || c >= 0x7F) return chain.proceed();
                final String full = String.valueOf((char) (c + 0xFEE0));
                try {
                    ((android.view.inputmethod.InputConnection) chain.getThisObject())
                            .commitText(full, 1);
                    sKeySwallowed.put(kc, android.os.SystemClock.uptimeMillis());
                    if (DEV_TRACE_ALNUM) {
                        Log.i(TAG, "fullwidth key kc=" + kc + " '" + (char) c + "' -> \"" + full + "\"");
                    }
                    return Boolean.TRUE;             // 吞掉按键：App 不会再自己插一遍
                } catch (Throwable tr) {
                    Log.w(TAG, "fullwidth key commit failed: " + tr);
                    return chain.proceed();
                }
            });
            Log.i(TAG, "norm hooked sendKeyEvent (物理键全角化)");
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "norm hook sendKeyEvent failed: " + tr);
            return false;
        }
    }

    /**
     * 诊断专用：把其它"会改文本/送键"的方法也挂上，只记日志、一律 proceed（不改行为）。
     *
     * <p>{@code getTextBeforeCursor} 这类**只读**方法不挂 —— 我们自己的 AutoPair 就在调它，
     * 挂上会自己刷自己。
     */
    private static int hookMutators(XposedModule module, Class<?> cls) {
        final String[] names = {"finishComposingText", "replaceText",
                "setComposingRegion", "deleteSurroundingText", "deleteSurroundingTextInCodePoints",
                "performEditorAction", "commitContent", "setSelection", "closeConnection"};
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            boolean want = false;
            for (String nm : names) {
                if (nm.equals(m.getName())) want = true;
            }
            if (!want) continue;
            try {
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final StringBuilder sb = new StringBuilder("IC* ").append(m.getName()).append('(');
                    final java.util.List<Object> as = chain.getArgs();
                    for (int i = 0; i < as.size(); i++) {
                        final Object a = as.get(i);
                        if (i > 0) sb.append(", ");
                        final String v = String.valueOf(a);
                        sb.append(v.length() > 40 ? v.substring(0, 40) + "…" : v);
                    }
                    Log.i(TAG, sb.append(")").toString());
                    return chain.proceed();
                });
                n++;
            } catch (Throwable tr) {
                Log.w(TAG, "norm diag hook " + m.getName() + " failed: " + tr);
            }
        }
        return n;
    }

    /** 串里有没有 ASCII 字母/数字（诊断用）。 */
    private static boolean looksAlnum(String s) {
        for (int i = 0; s != null && i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最近一次见到的输入连接（{@code RemoteInputConnection}）。
     *
     * <p>用途：**离线引擎**的识别结果比语音会话晚好几秒（模型加载 + 整段解码），Gboard 那时
     * 已经把会话收掉了、不再认结果（实测：sink emit 发了但输入框没字）。这时就直接用这个
     * 输入连接 {@code commitText} 把文字提交上去 —— 与"结果通道"相比它不依赖会话状态。
     */
    private static volatile Object sLastIc;

    static Object currentIc() {
        return sLastIc;
    }

    private static boolean hook(XposedModule module, Method m, String name) {
        try {
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a0 = chain.getArg(0);
                final Object self0 = chain.getThisObject();
                if (self0 instanceof android.view.inputmethod.InputConnection) sLastIc = self0;
                if (!(a0 instanceof CharSequence)) return chain.proceed();
                // 我们自己注入的闭字符：原样放行，别被标点管线二次改写
                if (AutoPair.isInjecting()) return chain.proceed();
                final boolean cn = ServiceProbe.isChinese();
                // Enter 探针（plan.md P0.2）：把组词/上屏的每一次都打出来，
                // 用来看"拼音在 Enter 那一刻是怎么上屏的"。只在这个探针开着时刷。
                // 拼音栏有没有字：setComposingText(非空) = 有；commitText = 这一段结束。
                // 中文态 Enter（EnterFix）就靠这个判据，所以**无条件**维护它（两次 volatile 写，很便宜）。
                if (name.equals("setComposingText")) {
                    EnterFix.setComposing(a0.toString().length() > 0);
                } else if (name.equals("commitText")) {
                    EnterFix.setComposing(false);
                }
                if (EnterFix.DEV_TRACE) {
                    Log.i(TAG, "enter IC " + name + "[\"" + a0 + "\"] cn=" + cn
                            + " @" + Thread.currentThread().getName());
                }
                String out = null;
                final String rawAll = a0.toString();
                if (cn) {
                    final String raw = rawAll;
                    // 智能编号：数字后面的 。/） 用半角。
                    // 判据比搜狗那版更稳：看提交文本的【末字符】，它前面（同一次提交里）或
                    // 上一次上屏的字符是数字就换 —— 这样 "2）" 一起上屏也能命中。
                    String base = raw;
                    if (sSmartNumber && !base.isEmpty()) {
                        final char tail = base.charAt(base.length() - 1);
                        if (tail == '\u3002' || tail == '\uFF09') {
                            final char prev = base.length() >= 2
                                    ? base.charAt(base.length() - 2)
                                    : beforeCursor(chain.getThisObject());
                            if (prev >= '0' && prev <= '9') {
                                base = base.substring(0, base.length() - 1)
                                        + (tail == '\u3002' ? "." : ")");
                            }
                        }
                    }
                    // —— 语义层（该出什么字符）——
                    // 智能中文标点（开关1）与中英文标点（开关3）：英文标点状态位开 ⇒ 按键盘显示的
                    // ASCII 标点输出（中文标点还原 + 下面宽度层拉回半角）。
                    String shaped = base;
                    final boolean en = sEnPunctFeature && GboardState.enPunct();
                    if (en) {
                        final String a = SymbolNorm.toAsciiPunct(shaped);
                        if (a != null) shaped = a;
                    } else if (sSmartPunct) {
                        final String r = SymbolNorm.applySemantic(shaped, SymbolNorm.longMarks());
                        if (r != null) shaped = r;
                    }
                    if (!shaped.equals(base)) out = shaped;
                }
                // —— 宽度层（宽窄）：**中英文都做** ——
                // 全角是一种"宽度偏好"，不该只管中文态（用户口径：全角要包含字母数字 ＡＢＣ１２３）。
                // 开 = 转全角；关 = 拉回半角（对 ASCII 是 no-op，所以英文态零回归）。
                // 范围：commitText 整段 ASCII；组合态只动符号 —— 中文态的组合态就是拼音串，
                // 连字母一起转会变成全角拼音 ｎｉｈａｏ（见 SymbolNorm.toFullWidthSymbols）。
                {
                    final String src = out != null ? out : rawAll;
                    final String w;
                    if (sFullWidthFeature && GboardState.fullwidth()) {
                        w = ("setComposingText".equals(name) && cn)
                                ? SymbolNorm.toFullWidthSymbols(src)
                                : SymbolNorm.toFullWidth(src);
                    } else {
                        w = SymbolNorm.toHalfWidth(src);
                    }
                    if (w != null) {
                        out = w;
                    }
                    sLast = out != null ? (out.isEmpty() ? 0 : out.charAt(out.length() - 1))
                            : (rawAll.isEmpty() ? 0 : rawAll.charAt(rawAll.length() - 1));
                }
                // 诊断：带全角字符的提交，无论改没改都打一行（只打这种，拼音字母不会刷屏）
                final String s0 = a0.toString();
                final boolean interesting = out != null || SymbolNorm.hasFullWidth(s0)
                        || s0.indexOf('/') >= 0 || s0.indexOf('\\') >= 0
                        || s0.indexOf('\u3001') >= 0;
                if (DEV_TRACE_ALNUM && looksAlnum(s0)) {
                    Log.i(TAG, "IC " + name + "[" + m.getParameterCount() + "] \"" + s0 + "\""
                            + " cn=" + cn + " full=" + (sFullWidthFeature && GboardState.fullwidth())
                            + (out == null ? "  (未命中)" : " -> \"" + out + "\""));
                }
                if (DEV_TRACE && cn && interesting) {
                    Log.i(TAG, "probe commit " + name + "[" + m.getParameterCount() + "] \""
                            + s0 + "\"" + (out == null ? "  (未命中)" : " -> \"" + out + "\""));
                    logStack(s0);
                }
                // 成对符号那一下（都要在 proceed **之前**问：之后选区/光标后就都变了）：
                //   ① 光标后已有同一个闭字符 ⇒ 只把光标移过去（closeSkip）
                //   ② 有选区 ⇒ 把选区包起来
                if (cn && "commitText".equals(name)
                        && AutoPair.maybeSkipClose(chain.getThisObject(),
                                out != null ? out : (CharSequence) a0)) {
                    return Boolean.TRUE;
                }
                if (cn && "commitText".equals(name)
                        && AutoPair.maybeWrapSelection(chain.getThisObject(),
                                out != null ? out : (CharSequence) a0)) {
                    return Boolean.TRUE;
                }
                final Object result;
                if (out == null) {
                    result = chain.proceed();
                } else {
                    final Object[] args = chain.getArgs().toArray();
                    args[0] = out;
                    result = chain.proceed(args);
                }
                // 开字符上屏后补闭字符（软/物理各由各自开关控制；只在 commitText 这条路上做）
                if (cn && "commitText".equals(name)) {
                    AutoPair.maybeInject(chain.getThisObject(), out != null ? out : (CharSequence) a0);
                }
                return result;
            });
            Log.i(TAG, "norm hooked " + name + "/" + m.getParameterCount());
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "norm hook " + name + " failed: " + tr);
            return false;
        }
    }
}
