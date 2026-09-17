package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;

/**
 * 模块侧配置轮询：每 2 秒问一次 App 的 ContentProvider，签名变了才动作。
 *
 * <p>照抄隔壁的设计 —— 不依赖输入法视图创建/会话开始那些时机（实测并不总会触发），
 * 所以"改完最多 2 秒生效，不用重启输入法"。
 */
final class ConfigWatch {

    private static final String TAG = "GboardExt";
    private static volatile String sLast;
    private static volatile boolean sStarted;

    /**
     * ContentProvider 轮询在 Gboard 上<b>走不通</b>：Gboard 的 targetSdk=36，
     * 受 Android 11+ 包可见性限制，看不见我们 App 的包（系统日志：
     * {@code Failed to find provider info for ...}）。Sogou 那边能用是因为它 targetSdk=29。
     * 下一步改用 libxposed 的 remote preferences（走框架自己的通道，不受可见性限制）。
     */
    private static final boolean USE_PROVIDER = false;

    private ConfigWatch() {}

    static void start(final Context ctx) {
        if (sStarted || ctx == null || !USE_PROVIDER) return;
        sStarted = true;
        final Thread t = new Thread(() -> {
            while (true) {
                try {
                    final String raw = GboardConfig.readProvider(ctx.getContentResolver());
                    final GboardConfig cfg = GboardConfig.parseDump(raw);
                    if (cfg != null) {
                        final String sig = cfg.signature();
                        if (sLast == null || !sig.equals(sLast)) {
                            sLast = sig;
                            SwitchGuard.setStrict(cfg.strict);
                            Log.i(TAG, "config -> " + sig);
                        }
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "config poll failed: " + tr);
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "gboardext-config");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "config watch started (2s)");
    }
}
