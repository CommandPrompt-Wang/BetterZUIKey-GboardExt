package moe.lovefirefly.bzk.gboardext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 补播链的一环 + 开机/升级入口。
 *
 * <p>收到就：① 发一次配置（Gboard 若在，立刻落盘）；② 排下一次补播。
 * 开机时 Gboard 还没起来，所以那时**只排链**，让后续几次去赶它。
 */
public class ConfigRetryReceiver extends BroadcastReceiver {

    private static final String TAG = "GboardExt";
    static final String EXTRA_INDEX = "retryIndex";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        final String action = intent == null ? null : intent.getAction();
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                Log.i(TAG, "retry: boot/updated -> schedule chain");
                ConfigRetry.schedule(context);
                return;
            }
            final int index = intent == null ? 0 : intent.getIntExtra(EXTRA_INDEX, 0);
            ConfigSender.send(context);
            ConfigRetry.scheduleStep(context, index + 1);
        } catch (Throwable tr) {
            Log.w(TAG, "retry onReceive failed: " + tr);
        }
    }
}
