package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 中文态 Enter 不误提交（配置项 {@code enterCommitPinyin}，**默认关**）。
 *
 * <p><b>要解决的问题</b>：在"按回车就提交"的输入框（浏览器地址/搜索框、聊天消息栏）里，
 * 拼音栏还有字时按 Enter 想保留原始拼音，结果整个框被提交掉了。
 *
 * <p><b>机制（2026-09 探针实测）</b>：Gboard 会吞掉物理 Enter（{@code onKeyDown -> true}）
 * 并在它内部把拼音上屏，然后**自己往 App 注入键事件**：
 * <ul>
 *   <li>拼音栏有字：只注入一颗 <b>抬起</b> —— 带 Shift 时 App 不提交（这就是 Shift+Enter
 *       那个 workaround 为什么管用），不带 Shift 就提交；</li>
 *   <li>拼音栏为空：注入 <b>按下+抬起</b> ⇒ 正常提交/换行。</li>
 * </ul>
 * 全程没有 {@code performEditorAction} / {@code sendDefaultEditorAction} —— 提交不是 IME action。
 *
 * <p><b>做法</b>（两步，都不自己上屏，拼音仍由 Gboard 自己提交，引擎内部状态天然同步）：
 * <ol>
 *   <li>把"有拼音时按的普通 Enter"整对（down + 配对的 up）改写为带 Shift 交给 Gboard
 *       ⇒ 它走自己的 Shift+Enter 路径：拼音照常上屏、注入的抬起带 Shift；</li>
 *   <li>再把这次"给拼音上屏用的"注入事件**吞掉** ⇒ App 一颗回车都收不到（对"只认注入事件"
 *       的框，如浏览器，这是兜底）。</li>
 * </ol>
 * down/up 必须<b>成对</b>改写（sticky）：down 之后 Gboard 的 {@code commitText} 会把 composing
 * 清成 false，只按 composing 门控就会漏掉 up ⇒ 注入的抬起不带 Shift ⇒ 照样被提交（踩过）。
 *
 * <p><b>已知边界</b>：① 软键盘上的 Enter 不走 {@code onKeyDown}，本功能不覆盖；
 * ② 有些 App（实测 QQ 消息栏）在<b>自己进程里</b>就把回车吃掉、根本不递给 IME ——
 * 那种情况 IME 侧无解（日志判据：{@code ENTER key seen} 一行都不会出现）。
 */
final class EnterFix {

    private static final String TAG = "GboardExt";
    private static final int KEYCODE_ENTER = 66;
    private static final int KEYCODE_NUMPAD_ENTER = 160;

    /** 配置开关（{@link GboardConfig#enterCommitPinyin}）。 */
    private static volatile boolean sEnabled;

    /** 拼音栏里有没有字（由 {@link SymbolNormHook} 在 IC 钩子上维护）。 */
    private static volatile boolean sComposing;

    /** 这次 Enter 的 down 已经改写 ⇒ 配对的 up 也要改写（见类注释的 sticky 说明）。 */
    private static volatile boolean sRewriteEnterUp;

    /** 这次 Enter（拼音上屏用的那次）Gboard 注入给 App 的键事件要吞掉。 */
    private static volatile boolean sSwallowInjected;

    /** 诊断（默认关）：打每一次 Enter 的事件与归属。 */
    static final boolean DEV_TRACE = false;

    /** 当前输入框属于哪个 App（便于排查"某 App 自己吃键"）。 */
    private static volatile String sEditorPkg = "?";

    private static volatile boolean sSvcHooked;
    private static volatile boolean sKeysHooked;
    private static volatile boolean sConnHooked;

    private EnterFix() {}

    static void setEnabled(boolean on) {
        if (sEnabled != on) Log.i(TAG, "enterCommitPinyin -> " + on);
        sEnabled = on;
    }

    static boolean enabled() {
        return sEnabled;
    }

    static void setComposing(boolean on) {
        sComposing = on;
    }

    static void setEditorPkg(String pkg) {
        sEditorPkg = pkg == null ? "?" : pkg;
    }

    /** IME 侧：往 App 注入键事件的那条路（只关心 Enter）。 */
    static void installConnection(XposedModule module, Object ic) {
        if (ic == null || sConnHooked) return;
        sConnHooked = true;
        for (Class<?> c = ic.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"sendKeyEvent".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != KeyEvent.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        final Object a = chain.getArg(0);
                        if (a instanceof KeyEvent) {
                            final KeyEvent ke = (KeyEvent) a;
                            if (isEnter(ke)) {
                                trace("inject", ke);
                                if (sSwallowInjected) {
                                    if (ke.getAction() == KeyEvent.ACTION_UP) {
                                        sSwallowInjected = false;
                                    }
                                    Log.i(TAG, "enter: swallowed injected Enter action="
                                            + ke.getAction() + " pkg=" + sEditorPkg);
                                    return Boolean.TRUE;
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "enter: hooked "
                            + m.getDeclaringClass().getSimpleName() + "#sendKeyEvent");
                } catch (Throwable tr) {
                    Log.w(TAG, "enter: hook sendKeyEvent failed: " + tr);
                }
            }
        }
    }

    /**
     * 按键那两条要挂到**实例自己的类**上，不能挂框架类。
     *
     * <p>实测：挂 {@code InputMethodService} 时只有 {@code onKeyUp} 有日志、{@code onKeyDown}
     * 一条都没有 —— Gboard 覆盖了后者，虚分派走它的实现。所以从实例的类沿父类链找同名同形参的
     * 方法（方法名是框架的，不涉及混淆名）。
     */
    static void installKeys(XposedModule module, Class<?> implClass) {
        if (implClass == null || sKeysHooked) return;
        sKeysHooked = true;
        int n = 0;
        for (Class<?> c = implClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("android.inputmethodservice.InputMethodService")) continue;
            for (Method m : c.getDeclaredMethods()) {
                final String name = m.getName();
                if (!name.equals("onKeyDown") && !name.equals("onKeyUp")) continue;
                final Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[0] != int.class || ps[1] != KeyEvent.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    final String where = c.getSimpleName() + "#" + name;
                    module.hook(m).intercept(chain -> {
                        final Object a = chain.getArg(1);
                        if (a instanceof KeyEvent) {
                            final KeyEvent ke = (KeyEvent) a;
                            if (isEnter(ke)) trace("key " + where, ke);
                            if (sEnabled) {
                                final Object r = interceptEnter(chain, where, ke);
                                if (r != null) return r;
                            }
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "enter: hooked " + where);
                    n++;
                } catch (Throwable tr) {
                    Log.w(TAG, "enter: hook " + name + " failed: " + tr);
                }
            }
        }
        Log.i(TAG, "enter: key hooks on " + implClass.getName() + " (" + n + ")");
    }

    /** 命中就返回"改写/放行后的结果"，没命中返回 {@code null}（调用方走原路）。 */
    private static Object interceptEnter(io.github.libxposed.api.XposedInterface.Chain chain,
            String where, KeyEvent ke) throws Throwable {
        // 拼音栏空着按的 Enter 不是"给拼音上屏"用的 ⇒ 清掉吞注入旗标，
        // 免得残留状态把后面真正该提交的那颗也吞了。
        if (ke.getAction() == KeyEvent.ACTION_DOWN && !sComposing) {
            sSwallowInjected = false;
            return null;
        }
        if (!ServiceProbe.isChinese()) return null;
        // 已经带修饰键的（Shift/Ctrl/Alt）不动：Shift+Enter 本来就是用户自己要的写法
        if ((ke.getMetaState() & (KeyEvent.META_SHIFT_ON | KeyEvent.META_CTRL_ON
                | KeyEvent.META_ALT_ON)) != 0) return null;
        if (!isEnter(ke)) return null;

        if (ke.getAction() == KeyEvent.ACTION_DOWN && sComposing) {
            sRewriteEnterUp = true;
            sSwallowInjected = true;
            Log.i(TAG, "enter: rewrite down -> Shift+Enter pkg=" + sEditorPkg);
            return chain.proceed(new Object[]{chain.getArg(0), withShift(ke)});
        }
        if (ke.getAction() == KeyEvent.ACTION_UP && sRewriteEnterUp) {
            sRewriteEnterUp = false;
            Log.i(TAG, "enter: rewrite up -> Shift+Enter pkg=" + sEditorPkg);
            return chain.proceed(new Object[]{chain.getArg(0), withShift(ke)});
        }
        return null;
    }

    private static boolean isEnter(KeyEvent ke) {
        return ke.getKeyCode() == KEYCODE_ENTER || ke.getKeyCode() == KEYCODE_NUMPAD_ENTER;
    }

    /** 复制一份 KeyEvent，但补上 SHIFT 修饰（与搜狗模块的 withoutShift 相反）。 */
    private static KeyEvent withShift(KeyEvent src) {
        final int meta = src.getMetaState() | KeyEvent.META_SHIFT_ON;
        final KeyEvent mod = new KeyEvent(src.getDownTime(), src.getEventTime(), src.getAction(),
                src.getKeyCode(), src.getRepeatCount(), meta, src.getDeviceId(),
                src.getScanCode(), src.getFlags());
        try {
            final java.lang.reflect.Field f = KeyEvent.class.getDeclaredField("mSource");
            f.setAccessible(true);
            f.setInt(mod, src.getSource());
        } catch (Throwable ignored) {
        }
        return mod;
    }

    private static void trace(String what, KeyEvent ke) {
        if (!DEV_TRACE) return;
        Log.i(TAG, "enter: " + what + " action=" + ke.getAction()
                + " meta=0x" + Integer.toHexString(ke.getMetaState())
                + " composing=" + sComposing + " pkg=" + sEditorPkg);
    }
}
