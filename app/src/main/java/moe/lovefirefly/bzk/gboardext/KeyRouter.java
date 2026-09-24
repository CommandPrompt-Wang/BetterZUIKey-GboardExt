package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 物理按键路由：本模块所有"要吃键/改键"的功能都从这一个挂点走。
 *
 * <p>为什么要单独一个类：同一个方法挂两次 hook 虽然能跑，但**顺序不可控**，而这里两件事
 * 有先后关系 —— 热键（Shift+Space / Ctrl+.）先判、判不掉再交给中文态 Enter 的处理。
 *
 * <p><b>挂哪条</b>：必须挂**输入法服务实例自己的类链**，不能挂框架类 ——
 * Gboard 覆盖了 {@code onKeyDown}，挂 {@code InputMethodService} 只能看到 {@code onKeyUp}
 * （实测：只挂框架类时 onKeyDown 一条日志都没有）。方法名是框架的，不涉及混淆名。
 *
 * <p><b>热键</b>（与搜狗模块一致；这两个组合在这台 OEM 上没有原生行为）：
 * <ul>
 *   <li>{@code Shift+Space} → 全角 / 半角（状态位），功能开关关掉时状态位被忽略；</li>
 *   <li>{@code Ctrl+.} → 中文标点 / 英文标点（状态位）；</li>
 * </ul>
 * 切完弹一行横幅（{@link Banner}）—— 搜狗那边 Toast 会被通知设置拦掉，沿用同一策略。
 */
final class KeyRouter {

    private static final String TAG = "GboardExt";

    private static volatile boolean sInstalled;

    /** 临时诊断开关：把 Shift / Space / 9 的按键事件原样打出来（查 Shift+Space 用）。 */
    private static final boolean DEV_TRACE_KEYS = false;

    /**
     * 这次 Shift 的**组合键被我们吃掉了**（Shift+Space / Ctrl+Shift+9）⇒ 抬起要特殊处理。
     *
     * <p>为什么：Gboard 中文态下 <b>Shift 单击 = 切中/英</b>，而它判"单击"看的是抬起事件；
     * 我们把组合键吃掉后 Gboard 看不到任何别的键，于是把这次 Shift 当成单击（提交 `bcf8779`）。
     * 处理方式见 {@link #shiftTapGuard}（伪装成长按后放行，既不清不掉状态也不切语言）。
     *
     * <p><b>⚠️ 踩过的坑（这个标记只能标记"我们吃掉的那个键"）</b>：第一版写成"任何带 SHIFT 修饰的
     * 按下都标记"，于是 Shift+字母 / Shift+数字 这种 <b>Gboard 自己看得见组合键</b>的情况也被吞了抬起：
     * <ol>
     *   <li>Gboard 的 shift 保持态再也没被清掉 —— 表现是候选窗数字消失后<b>一直不恢复</b>，
     *       直到下次单独单击 Shift（用户实测复现）；</li>
     *   <li>抬起事件没往下传 ⇒ 把物理键转发到远端的 App（如 ToDesk）会变成<b>远端一直按着 Shift</b>。</li>
     * </ol>
     * 结论：需要吞的只有"我们吃掉组合键"这一种，别的键一律别碰（Gboard 自己会把 shift 用掉）。
     */
    private static volatile boolean sShiftEatenKey;

    /**
     * 最近一次 Shift 按键的时间。
     *
     * <p>用途：Gboard 自己的 **Shift 单击 = 中/英** 是另一套功能（仅中英），
     * 用户明确要求"不必干预它" ⇒ 严格模式不拦、顺序轮转也不接管紧跟 Shift 的那次切换。
     */
    private static volatile long sShiftAt;

    private KeyRouter() {}

    static void install(XposedModule module, Class<?> implClass) {
        if (implClass == null || sInstalled) return;
        sInstalled = true;
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
                            final int kc0 = ke.getKeyCode();
                            if (kc0 == KeyEvent.KEYCODE_SHIFT_LEFT
                                    || kc0 == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                                sShiftAt = android.os.SystemClock.uptimeMillis();
                            }
                            if (DEV_TRACE_KEYS && (kc0 == KeyEvent.KEYCODE_SHIFT_LEFT
                                    || kc0 == KeyEvent.KEYCODE_SHIFT_RIGHT
                                    || kc0 == KeyEvent.KEYCODE_SPACE
                                    || kc0 == KeyEvent.KEYCODE_9)) {
                                Log.i(TAG, "key " + name + " kc=" + kc0
                                        + " act=" + ke.getAction()
                                        + " meta=0x" + Integer.toHexString(ke.getMetaState())
                                        + " rep=" + ke.getRepeatCount()
                                        + " hold=" + (ke.getEventTime() - ke.getDownTime())
                                        + " @@" + android.os.SystemClock.uptimeMillis()
                                        + " shiftJust=" + shiftJustPressed());
                            }
                            // "这次提交来自物理键盘"——软键盘不走 onKeyDown/onKeyUp，天然分得开。
                            // 必须按下置 true、抬起置 false（照搜狗）：只置 true 不复位的话，
                            // 按过一次物理键之后所有软键盘提交都会被当成物理来源 ⇒ 配对不生效。
                            AutoPair.setHardwareKey(name.equals("onKeyDown"));
                            if (shiftTapGuard(ke)) {
                                return Boolean.TRUE;   // 只有"被我们吃掉组合键的那次 Shift 抬起"会走到这
                            }
                            // 1) 热键（可能吃掉）
                            final Object hot = hotkey(chain, name, ke);
                            if (hot != null) return hot;
                            // 2) 中文态 Enter（可能改写/吃掉）
                            final Object ent = EnterFix.interceptKey(chain, ke);
                            if (ent != null) return ent;
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "keys: hooked " + where);
                    n++;
                } catch (Throwable tr) {
                    Log.w(TAG, "keys: hook " + name + " failed: " + tr);
                }
            }
        }
        Log.i(TAG, "keys: installed on " + implClass.getName() + " (" + n + ")");
    }

    /**
     * Shift 抬起守卫：**只**在我们吃掉了它的组合键时吞掉那次抬起（原因见 {@link #sShiftEatenKey}）。
     *
     * <p><b>这个"吞"是权衡后的选择，两次改良都实测失败（2026-09-24，都记在这）</b>：
     * <ol>
     *   <li>不能"只标记任何带 Shift 的按键" —— 那会把 Shift+字母 的抬起也吞掉：Gboard 的 shift
     *       保持态清不掉（候选窗数字不恢复），而且抬起不往下传，转发物理键到远端的 App（ToDesk）
     *       会一直按着 Shift（用户实测复现）。<b>⇒ 现在只标记"我们吃掉的组合键"。</b></li>
     *   <li>想让被吃的组合键也不吞抬起，试过<b>把 downTime 伪装成长按</b>：实测无效 ——
     *       改写确实生效（日志里事件 hold=400），Gboard 照样切语言 ⇒ 它判"单击"**不看事件的
     *       downTime**，用自己内部的时间/标记。</li>
     *   <li>又试过<b>抬起放行 + 把这次抬起引发的切换吞掉</b>（拦 {@code switchInputMethod}/
     *       {@code setInputMethodAndSubtype}）：日志显示拦截确实命中
     *       （{@code switchcall svc.switchInputMethod -> BLOCK}），但语言<b>照样变了</b> ——
     *       Gboard 是<b>先在内部改完语言、然后才发这个调用通知系统</b>，拦调用太晚。</li>
     * </ol>
     * ⇒ 结论：在"不换快捷键"的前提下，只能在"语言被切"和"shift 保持态卡住"之间二选一，
     * 这里选后者（语言是用户明确要保住的）。真正干净的办法是把全角/半角换到<b>不带 Shift</b>
     * 的快捷键上 —— 那样 {@link #noteShiftEaten} 永远不会被触发，整个守卫都不需要，
     * 见 local/plan.md §20。
     *
     * @return true = 吞掉这次事件
     */
    private static boolean shiftTapGuard(KeyEvent ke) {
        final int kc = ke.getKeyCode();
        final boolean isShift = kc == KeyEvent.KEYCODE_SHIFT_LEFT
                || kc == KeyEvent.KEYCODE_SHIFT_RIGHT;
        if (!isShift) return false;          // 别的键不在这里判定：Gboard 自己看得见它
        if (ke.getAction() == KeyEvent.ACTION_DOWN) {
            if (ke.getRepeatCount() == 0) sShiftEatenKey = false;   // 新的 Shift 按下：先当作没被吃
            return false;
        }
        if (sShiftEatenKey) {                // 抬起：这次 Shift 的组合键被我们吃了 ⇒ 吞掉抬起
            sShiftEatenKey = false;
            Log.i(TAG, "shift: swallowed up (we ate the combo key)");
            return true;
        }
        return false;
    }

    /** 我们吃掉了一个"带 Shift 的组合键" ⇒ 记下来，等 Shift 抬起时按长按放行（见 {@link #shiftTapGuard}）。 */
    private static void noteShiftEaten() {
        sShiftEatenKey = true;
    }

    /** 刚刚（600ms 内）按过 Shift ⇒ 这次切换是 Gboard 的"Shift 单击中/英"，放行不管。 */
    static boolean shiftJustPressed() {
        final long t = sShiftAt;
        return t != 0 && android.os.SystemClock.uptimeMillis() - t < 600;
    }

    /** 命中就返回结果（吃键或改写后放行），没命中返回 {@code null}。 */
    private static Object hotkey(XposedInterface.Chain chain, String name, KeyEvent ke)
            throws Throwable {
        final boolean down = name.equals("onKeyDown");
        final int meta = ke.getMetaState();
        final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;

        // Shift+Space → 全角 / 半角
        if (ke.getKeyCode() == KeyEvent.KEYCODE_SPACE && shift && !ctrl) {
            if (down && ke.getRepeatCount() == 0) {
                noteShiftEaten();                      // 这个组合键被我们吃了 ⇒ Shift 抬起要吞（见 shiftTapGuard）
                final boolean on = !GboardState.fullwidth();
                GboardState.setFullwidth(on);
                Log.i(TAG, "hotkey Shift+Space -> fullwidth=" + on);
                Banner.show(SymbolNormHook.fullWidthFeature()
                        ? "全角模式：" + (on ? "开" : "关")
                        : "全角模式：功能已关闭（设置里打开才生效）");
            }
            return Boolean.TRUE;                       // 这个组合不给 Gboard
        }

        // Ctrl+Shift+9 → 物理键盘自动补全的状态位（功能开关之下的临时开关）
        if (ke.getKeyCode() == KeyEvent.KEYCODE_9 && ctrl && shift) {
            if (down && ke.getRepeatCount() == 0) {
                noteShiftEaten();                      // 同上：吃了组合键就得吞 Shift 抬起
                final boolean on = !GboardState.physComplete();
                GboardState.setPhysComplete(on);
                Log.i(TAG, "hotkey Ctrl+Shift+9 -> physComplete=" + on);
                Banner.show(SymbolNormHook.physCompleteFeature()
                        ? "物理键盘补全：" + (on ? "开" : "关")
                        : "物理键盘补全：功能已关闭（设置里打开才生效）");
            }
            return Boolean.TRUE;
        }

        // Ctrl+. → 中文标点 / 英文标点
        if (ke.getKeyCode() == KeyEvent.KEYCODE_PERIOD && ctrl) {
            if (down && ke.getRepeatCount() == 0) {
                final boolean on = !GboardState.enPunct();
                GboardState.setEnPunct(on);
                Log.i(TAG, "hotkey Ctrl+. -> enPunct=" + on);
                Banner.show(SymbolNormHook.enPunctFeature()
                        ? "标点模式：" + (on ? "英文标点" : "中文标点")
                        : "中英文标点：功能已关闭（设置里打开才生效）");
            }
            return Boolean.TRUE;
        }
        return null;
    }
}
