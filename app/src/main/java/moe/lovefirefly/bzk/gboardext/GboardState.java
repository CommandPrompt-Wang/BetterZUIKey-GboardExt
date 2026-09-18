package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 状态位：**不在界面上**的"当前是哪一档"，存在目标进程（Gboard）自己的 prefs 里。
 *
 * <p>与"功能开关"（App 界面 → 广播 → {@link GboardConfig}）分开，这是照搜狗那边的约定：
 * 功能开关决定"这个功能启不启用"，状态位决定"启用后当前是哪一档"，由快捷键切换并持久化。
 *
 * <table>
 *   <tr><th>状态位</th><th>默认</th><th>快捷键</th><th>功能开关关掉时</th></tr>
 *   <tr><td>{@code fullwidth} 全角/半角</td><td>半角</td><td>Shift+Space</td><td>恒半角</td></tr>
 *   <tr><td>{@code enPunct} 中文/英文标点</td><td>中文标点</td><td>Ctrl+.</td><td>恒中文标点</td></tr>
 * </table>
 *
 * <p>默认值刻意取"与今天行为一致"的那一档 ⇒ 加开关**零回归**。
 */
final class GboardState {

    private static final String TAG = "GboardExt";
    private static final String K_FULL = "stateFullwidth";
    private static final String K_EN = "stateEnPunct";
    private static final String K_PHYS = "statePhysComplete";

    private static volatile Context sCtx;
    private static volatile Boolean sFull;
    private static volatile Boolean sEn;
    private static volatile Boolean sPhys;

    private GboardState() {}

    /** 用输入法服务自己的 Context（= Gboard 的包上下文，写进它的数据目录）。 */
    static void attach(Context ctx) {
        if (ctx != null) sCtx = ctx;
    }

    /** 全角模式的状态位：true = 全角。功能开关关掉时调用方不该读它。 */
    static boolean fullwidth() {
        Boolean v = sFull;
        if (v == null) {
            final SharedPreferences sp = prefs();
            v = sp != null && sp.getBoolean(K_FULL, false);      // 默认半角
            sFull = v;
        }
        return v;
    }

    /** 中英文标点的状态位：true = 英文标点。 */
    static boolean enPunct() {
        Boolean v = sEn;
        if (v == null) {
            final SharedPreferences sp = prefs();
            v = sp != null && sp.getBoolean(K_EN, false);        // 默认中文标点
            sEn = v;
        }
        return v;
    }

    static void setFullwidth(boolean on) {
        sFull = on;
        save(K_FULL, on);
        mirror();
    }

    /** 物理补全的状态位：默认开（功能开关打开后开箱即用）。 */
    static boolean physComplete() {
        Boolean v = sPhys;
        if (v == null) {
            final SharedPreferences sp = prefs();
            v = sp == null || sp.getBoolean(K_PHYS, true);
            sPhys = v;
        }
        return v;
    }

    static void setPhysComplete(boolean on) {
        sPhys = on;
        save(K_PHYS, on);
        mirror();
    }

    static void setEnPunct(boolean on) {
        sEn = on;
        save(K_EN, on);
        mirror();
    }

    /**
     * 把三个状态位回传给设置页（「当前状态」显示用）。
     *
     * <p>只在**状态位真的变化**时调用（三个 setter 里），热键那一刻发一次即可；
     * 设置页不在前台时广播丢掉无所谓 —— 它下次进页面会补发配置，届时状态位照旧。
     */
    private static void mirror() {
        mirrorNow();
    }

    /** 立刻回传一次当前状态位（热键变化时、以及设置页主动索要时）。 */
    static void mirrorNow() {
        BroadcastConfig.sendState(sCtx, fullwidth(), enPunct(), physComplete());
    }

    private static void save(String key, boolean on) {
        final SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putBoolean(key, on).apply();
    }

    private static SharedPreferences prefs() {
        final Context c = sCtx;
        if (c == null) return null;
        try {
            return c.getSharedPreferences(BroadcastConfig.STATE_PREFS, Context.MODE_PRIVATE);
        } catch (Throwable tr) {
            Log.w(TAG, "state prefs unavailable: " + tr);
            return null;
        }
    }
}
