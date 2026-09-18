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

    /**
     * 这次 Shift 被"用掉了"（和别的键组成组合键）⇒ 抬起要吞掉。
     *
     * <p>为什么：Gboard 中文态下 <b>Shift 单击 = 切中/英</b>，而它判"单击"看的是抬起事件
     * （用户实测：Shift+Space 会连带把语言切了）。搜狗模块当年也是这么处理的
     * （"这次 Shift 被用于字母：吞掉它的抬起，免得搜狗当成 Shift 单击切语言"）。
     * 吞掉抬起的代价：单独按一下再松开 Shift 仍然照常切语言（那才是用户想要的）。
     */
    private static volatile boolean sShiftUsed;

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
                            // "这次提交来自物理键盘"——软键盘不走 onKeyDown/onKeyUp，天然分得开。
                            // 必须按下置 true、抬起置 false（照搜狗）：只置 true 不复位的话，
                            // 按过一次物理键之后所有软键盘提交都会被当成物理来源 ⇒ 配对不生效。
                            AutoPair.setHardwareKey(name.equals("onKeyDown"));
                            if (shiftTapGuard(ke)) return Boolean.TRUE;
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
     * Shift 单击守卫：Shift 与别的键组合后，把它那次**抬起**吞掉，免得 Gboard 当成"单击切中/英"。
     *
     * <p>返回 true 表示这次事件已被吞掉。
     */
    private static boolean shiftTapGuard(KeyEvent ke) {
        final int kc = ke.getKeyCode();
        final boolean isShift = kc == KeyEvent.KEYCODE_SHIFT_LEFT
                || kc == KeyEvent.KEYCODE_SHIFT_RIGHT;
        if (isShift) {
            if (ke.getAction() == KeyEvent.ACTION_DOWN) {
                sShiftUsed = false;                 // 新的 Shift 按下：还没被用掉
                return false;
            }
            if (sShiftUsed) {                       // 抬起：这次 Shift 用过 ⇒ 吞掉
                sShiftUsed = false;
                Log.i(TAG, "shift: swallowed up (used with another key)");
                return true;
            }
            return false;
        }
        if (ke.getAction() == KeyEvent.ACTION_DOWN
                && (ke.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
            sShiftUsed = true;                      // 这一下 Shift 是组合的一部分
        }
        return false;
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
