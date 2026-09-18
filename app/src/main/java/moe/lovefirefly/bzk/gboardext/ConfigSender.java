package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 把配置广播给 Gboard（App 侧唯一的发送入口）。
 *
 * <p>抽成独立的类是因为**不止一个地方要发**：设置页（{@link MainActivity}）、
 * 补播（{@link ConfigRetry}）、开机/升级（{@link ConfigRetryReceiver}）。
 *
 * <p>为什么要补播：接收器是模块在 Gboard 进程里**运行时注册**的 —— Gboard 进程不在时
 * 广播直接丢掉（实测：force-stop 之后打开 App，`gboardext_state.xml` 里只有状态位、
 * 没有配置键）。而真实的"首次投递"场景恰恰是改设置时 Gboard 没在跑、等用户下次打字它才起来。
 */
final class ConfigSender {

    private static final String TAG = "GboardExt";

    private ConfigSender() {}

    /** 直接在**当前进程**（App）里发一条广播。 */
    static void send(Context ctx) {
        try {
            send(ctx, ctx.getSharedPreferences(GboardConfig.PREFS_NAME, Context.MODE_PRIVATE));
        } catch (Throwable tr) {
            Log.w(TAG, "send failed: " + tr);
        }
    }

    /** 广播接收器里也要发（那时进程可能刚被拉起来）。 */
    static void send(Context ctx, SharedPreferences prefs) {
        try {
            final Intent i = new Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.TARGET_PKG);
            i.putExtra(BroadcastConfig.EXTRA_STRICT,
                    prefs.getBoolean(GboardConfig.KEY_STRICT, true));
            i.putExtra(BroadcastConfig.EXTRA_LONG,
                    prefs.getBoolean(GboardConfig.KEY_LONG, true));
            i.putExtra(BroadcastConfig.EXTRA_NUMBER,
                    prefs.getBoolean(GboardConfig.KEY_NUMBER, true));
            i.putExtra(BroadcastConfig.EXTRA_ENTER,
                    prefs.getBoolean(GboardConfig.KEY_ENTER, false));
            i.putExtra(BroadcastConfig.EXTRA_SMART_PUNCT,
                    prefs.getBoolean(GboardConfig.KEY_SMART_PUNCT, true));
            i.putExtra(BroadcastConfig.EXTRA_FULLWIDTH,
                    prefs.getBoolean(GboardConfig.KEY_FULLWIDTH, true));
            i.putExtra(BroadcastConfig.EXTRA_EN_PUNCT,
                    prefs.getBoolean(GboardConfig.KEY_EN_PUNCT, true));
            // 引号/括号自动补全那三项（漏发过一次 ⇒ 模块收到 null 配对表 ⇒ 静默不配对）
            i.putExtra(BroadcastConfig.EXTRA_AUTO_PAIR,
                    prefs.getBoolean(GboardConfig.KEY_AUTO_PAIR, false));
            i.putExtra(BroadcastConfig.EXTRA_PHYS_COMPLETE,
                    prefs.getBoolean(GboardConfig.KEY_PHYS_COMPLETE, false));
            i.putExtra(BroadcastConfig.EXTRA_PAIR_TABLE,
                    prefs.getString(GboardConfig.KEY_PAIR_TABLE, GboardPair.DEFAULT_TABLE));
            i.putExtra(BroadcastConfig.EXTRA_OVERRIDE_ROTATION,
                    prefs.getBoolean(GboardConfig.KEY_OVERRIDE_ROTATION, false));
            i.putExtra(BroadcastConfig.EXTRA_ROTATION_ORDER,
                    prefs.getString(GboardConfig.KEY_ROTATION_ORDER, ""));
            ctx.sendBroadcast(i);
            Log.i(TAG, "config sent");
        } catch (Throwable tr) {
            Log.w(TAG, "send failed: " + tr);
        }
    }
}
