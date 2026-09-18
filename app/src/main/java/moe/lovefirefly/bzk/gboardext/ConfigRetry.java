package moe.lovefirefly.bzk.gboardext;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 配置**补播**：变更/开机之后，隔几个时间点再发几次，直到 Gboard 真的收到为止。
 *
 * <p>背景见 {@link ConfigSender}：广播只在 Gboard 进程活着时能被收到，而"改设置的那一刻"
 * Gboard 往往不在（用户是先在设置页拨开关，之后才去打字）。所以这里排一条**有界的**重试链：
 * 每个时间点各发一次，谁赶上 Gboard 起床谁就把它落盘（模块收到即持久化，之后 Gboard 重启也不丢）。
 *
 * <p>用 {@code AlarmManager.set}（非精确）而不是 exact：不需要 SCHEDULE_EXACT_ALARM 权限，
 * 差几分钟无所谓 —— 真正的目标是"覆盖用户接下来这半小时里的第一次打字"。
 */
final class ConfigRetry {

    private static final String TAG = "GboardExt";

    /** 变更后在这些延迟（毫秒）各补播一次；第 0 项立即发。 */
    private static final long[] DELAYS = {0L, 10_000L, 30_000L, 60_000L, 180_000L, 600_000L,
            1_800_000L};

    private ConfigRetry() {}

    static void schedule(Context ctx) {
        scheduleStep(ctx, 0);
    }

    static void scheduleStep(Context ctx, int index) {
        if (ctx == null || index < 0 || index >= DELAYS.length) return;
        try {
            final Intent i = new Intent(ctx, ConfigRetryReceiver.class);
            i.putExtra(ConfigRetryReceiver.EXTRA_INDEX, index);
            final PendingIntent pi = PendingIntent.getBroadcast(ctx, index, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + DELAYS[index], pi);
            Log.i(TAG, "config retry scheduled #" + index + " in " + DELAYS[index] + "ms");
        } catch (Throwable tr) {
            Log.w(TAG, "schedule retry failed: " + tr);
        }
    }
}
