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
                                    || kc0 == KeyEvent.KEYCODE_9
                                    || (kc0 >= KeyEvent.KEYCODE_0 && kc0 <= KeyEvent.KEYCODE_9))) {
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

    /** 刚刚（600ms 内）按过 Shift ⇒ 这次切换是 Gboard 的"Shift 单击中/英"，放行不管。 */
    static boolean shiftJustPressed() {
        final long t = sShiftAt;
        return t != 0 && android.os.SystemClock.uptimeMillis() - t < 600;
    }

    /**
     * 命中就返回结果（吃键或改写后放行），没命中返回 {@code null}。
     *
     * <p><b>带 Shift 的组合键：按下必须吃、抬起必须放行</b>（踩了很久，见 local/plan.md §20）：
     * <ul>
     *   <li>按下吃了才不会打空格 / 中文态选走第一个候选词；</li>
     *   <li>抬起<b>一定要放行</b> —— Gboard 判"Shift 单击 = 切中/英"看的是"这期间有没有别的键"。
     *       以前把按下和抬起都吃了，Gboard 只看见 Shift ⇒ 当成单击切语言；于是又去吞 Shift 的抬起，
     *       结果 shift 保持态清不掉（候选窗数字不恢复）、抬起也不往下传（ToDesk 这类转发物理键到
     *       远端的 App 会一直按着 Shift）。放行抬起后 Gboard 就知道"这个 Shift 被用掉了"，
     *       判定阶段直接不进（日志里 switchcall 一次都不出现），**吞抬起那套守卫整个不需要了**。</li>
     * </ul>
     */
    private static Object hotkey(XposedInterface.Chain chain, String name, KeyEvent ke)
            throws Throwable {
        final boolean down = name.equals("onKeyDown");
        final int meta = ke.getMetaState();
        final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;

        // Shift+Space → 全角 / 半角
        if (ke.getKeyCode() == KeyEvent.KEYCODE_SPACE && shift && !ctrl) {
            if (down && ke.getRepeatCount() == 0) {
                final boolean on = !GboardState.fullwidth();
                GboardState.setFullwidth(on);
                Log.i(TAG, "hotkey Shift+Space -> fullwidth=" + on);
                Banner.show(SymbolNormHook.fullWidthFeature()
                        ? "全角模式：" + (on ? "开" : "关")
                        : "全角模式：功能已关闭（设置里打开才生效）");
            }
            // **按下吃掉、抬起放行**（关键，别改成两个都吃）：见 hotkey 顶部的说明
            return down ? Boolean.TRUE : null;
        }

        // Ctrl+Shift+9 → 物理键盘自动补全的状态位（功能开关之下的临时开关）
        if (ke.getKeyCode() == KeyEvent.KEYCODE_9 && ctrl && shift) {
            if (down && ke.getRepeatCount() == 0) {
                final boolean on = !GboardState.physComplete();
                GboardState.setPhysComplete(on);
                Log.i(TAG, "hotkey Ctrl+Shift+9 -> physComplete=" + on);
                Banner.show(SymbolNormHook.physCompleteFeature()
                        ? "物理键盘补全：" + (on ? "开" : "关")
                        : "物理键盘补全：功能已关闭（设置里打开才生效）");
            }
            return down ? Boolean.TRUE : null;      // 同 Shift+Space：按下吃、抬起放行
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
