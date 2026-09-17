package moe.lovefirefly.bzk.gboardext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * 配置通道：**显式广播 + 运行时注册的接收器**。
 *
 * <p>为什么不用 ContentProvider：Gboard 的 targetSdk=36，受 Android 11+ 包可见性过滤，
 * 它<b>看不见我们 App 的包</b>（实测 {@code Failed to find provider info}）；而可见性只限制
 * <b>发起方</b> —— 反过来由我们（能看见 Gboard）发显式广播给它，就能通。
 *
 * <p>接收端不需要改 Gboard 的清单：本类在 Gboard 进程里用输入法服务自己的 Context
 * <b>运行时注册</b>，不要求宿主 APK 声明任何东西。
 */
final class BroadcastConfig {

    private static final String TAG = "GboardExt";
    static final String ACTION = "moe.lovefirefly.bzk.gboardext.CONFIG";
    static final String EXTRA_STRICT = "strict";
    static final String EXTRA_LONG = "longMarks";

    private static volatile boolean sStarted;

    private BroadcastConfig() {}

    static void start(final Context ctx) {
        if (sStarted || ctx == null) return;
        sStarted = true;
        try {
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    final boolean strict = intent.getBooleanExtra(EXTRA_STRICT, true);
                    SwitchGuard.setStrict(strict);
                    final boolean longMarks = intent.getBooleanExtra(EXTRA_LONG, true);
                    SymbolNorm.setLongMarks(longMarks);
                    Log.i(TAG, "config broadcast: strict=" + strict
                            + ", longMarks=" + longMarks);
                }
            };
            final IntentFilter filter = new IntentFilter(ACTION);
            // targetSdk 34+ 起，跨应用接收必须显式声明导出标志
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            Log.i(TAG, "config broadcast receiver registered");
        } catch (Throwable tr) {
            Log.w(TAG, "config receiver failed: " + tr);
        }
    }
}
