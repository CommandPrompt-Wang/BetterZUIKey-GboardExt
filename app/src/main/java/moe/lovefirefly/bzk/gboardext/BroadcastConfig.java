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
    static final String EXTRA_NUMBER = "smartNumbering";
    static final String EXTRA_ENTER = "enterCommitPinyin";
    static final String EXTRA_SMART_PUNCT = "smartPunct";
    static final String EXTRA_FULLWIDTH = "fullwidth";
    static final String EXTRA_EN_PUNCT = "enPunct";
    static final String EXTRA_AUTO_PAIR = "autoPair";
    static final String EXTRA_PHYS_COMPLETE = "physComplete";
    static final String EXTRA_PAIR_TABLE = "autoPairTable";

    /**
     * 落盘用的 prefs（写在**目标进程**（Gboard）自己的数据目录里）。
     *
     * <p>为什么需要：本模块的配置只靠广播推过来，是"一次性"的 —— Gboard 进程一重启就回到
     * 编译期默认值，开关会**悄悄回默认**（新加的中文态 Enter 尤其致命：默认关 ⇒ 用户明明打开了
     * 却时不时失效）。provider 那条又走不通（Gboard targetSdk 36 的包可见性，§15），
     * 所以改成广播到达时写进目标进程 prefs，模块启动时先读它当初始值。
     */
    static final String STATE_PREFS = "gboardext_state";
    private static final String K_STRICT = "strict";
    private static final String K_LONG = "longMarks";
    private static final String K_NUMBER = "smartNumbering";
    private static final String K_ENTER = "enterCommitPinyin";
    private static final String K_SMART_PUNCT = "smartPunct";
    private static final String K_FULLWIDTH = "fullwidth";
    private static final String K_EN_PUNCT = "enPunct";
    private static final String K_AUTO_PAIR = "autoPair";
    private static final String K_PHYS_COMPLETE = "physComplete";
    private static final String K_PAIR_TABLE = "autoPairTable";

    private static volatile boolean sStarted;

    private BroadcastConfig() {}

    static void start(final Context ctx) {
        if (sStarted || ctx == null) return;
        sStarted = true;
        try {
            apply(ctx, loadPersisted(ctx));      // 先用上次广播落盘的值（Gboard 重启后靠这条）
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    final boolean strict = intent.getBooleanExtra(EXTRA_STRICT, true);
                    final boolean longMarks = intent.getBooleanExtra(EXTRA_LONG, true);
                    final boolean smartNumbering =
                            intent.getBooleanExtra(EXTRA_NUMBER, true);
                    final boolean enter = intent.getBooleanExtra(EXTRA_ENTER, false);
                    final boolean smartPunct = intent.getBooleanExtra(EXTRA_SMART_PUNCT, true);
                    final boolean fullwidth = intent.getBooleanExtra(EXTRA_FULLWIDTH, true);
                    final boolean enPunct = intent.getBooleanExtra(EXTRA_EN_PUNCT, true);
                    final boolean autoPair = intent.getBooleanExtra(EXTRA_AUTO_PAIR, false);
                    final boolean physComplete =
                            intent.getBooleanExtra(EXTRA_PHYS_COMPLETE, false);
                    final String pairTable = intent.getStringExtra(EXTRA_PAIR_TABLE);
                    applyValues(strict, longMarks, smartNumbering, enter,
                            smartPunct, fullwidth, enPunct, autoPair, physComplete, pairTable);
                    persist(c == null ? ctx : c, strict, longMarks, smartNumbering, enter,
                            smartPunct, fullwidth, enPunct, autoPair, physComplete, pairTable);
                    Log.i(TAG, "config broadcast: strict=" + strict
                            + ", longMarks=" + longMarks + ", num=" + smartNumbering
                            + ", enter=" + enter + ", smartPunct=" + smartPunct
                            + ", fullwidth=" + fullwidth + ", enPunct=" + enPunct
                            + ", autoPair=" + autoPair + ", physComplete=" + physComplete);
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

    // ---------------------------------------------------------------- 应用 / 落盘

    /** 一个包里就七个布尔，用数组传，省得再堆一个类。 */
    private static void apply(Context ctx, Object[] v) {
        if (v == null) return;
        applyValues((Boolean) v[0], (Boolean) v[1], (Boolean) v[2], (Boolean) v[3],
                (Boolean) v[4], (Boolean) v[5], (Boolean) v[6], (Boolean) v[7], (Boolean) v[8],
                (String) v[9]);
    }

    private static void applyValues(boolean strict, boolean longMarks, boolean smartNumbering,
            boolean enter, boolean smartPunct, boolean fullwidth, boolean enPunct,
            boolean autoPair, boolean physComplete, String pairTable) {
        SwitchGuard.setStrict(strict);
        SymbolNorm.setLongMarks(longMarks);
        SymbolNormHook.setSmartNumber(smartNumbering);
        EnterFix.setEnabled(enter);
        SymbolNormHook.setSmartPunct(smartPunct);
        SymbolNormHook.setFullWidthFeature(fullwidth);
        SymbolNormHook.setEnPunctFeature(enPunct);
        AutoPair.setEnabled(autoPair);
        AutoPair.setPhysEnabled(physComplete);
        SymbolNormHook.setPhysCompleteFeature(physComplete);
        AutoPair.setTable(pairTable);
    }

    private static void persist(Context ctx, boolean strict, boolean longMarks,
            boolean smartNumbering, boolean enter, boolean smartPunct, boolean fullwidth,
            boolean enPunct, boolean autoPair, boolean physComplete, String pairTable) {
        try {
            ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(K_STRICT, strict)
                    .putBoolean(K_LONG, longMarks)
                    .putBoolean(K_NUMBER, smartNumbering)
                    .putBoolean(K_ENTER, enter)
                    .putBoolean(K_SMART_PUNCT, smartPunct)
                    .putBoolean(K_FULLWIDTH, fullwidth)
                    .putBoolean(K_EN_PUNCT, enPunct)
                    .putBoolean(K_AUTO_PAIR, autoPair)
                    .putBoolean(K_PHYS_COMPLETE, physComplete)
                    .putString(K_PAIR_TABLE, pairTable == null
                            ? GboardPair.DEFAULT_TABLE : pairTable)
                    .apply();
        } catch (Throwable tr) {
            Log.w(TAG, "config persist failed: " + tr);
        }
    }

    /** 读上次落盘的值；没有就返回 {@code null}（让各自的编译期默认值生效）。 */
    private static Object[] loadPersisted(Context ctx) {
        try {
            final android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            if (!sp.contains(K_STRICT)) return null;
            final Object[] v = new Object[]{
                    sp.getBoolean(K_STRICT, true),
                    sp.getBoolean(K_LONG, true),
                    sp.getBoolean(K_NUMBER, true),
                    sp.getBoolean(K_ENTER, false),
                    sp.getBoolean(K_SMART_PUNCT, true),
                    sp.getBoolean(K_FULLWIDTH, true),
                    sp.getBoolean(K_EN_PUNCT, true),
                    sp.getBoolean(K_AUTO_PAIR, false),
                    sp.getBoolean(K_PHYS_COMPLETE, false),
                    sp.getString(K_PAIR_TABLE, GboardPair.DEFAULT_TABLE)};
            Log.i(TAG, "config restored: strict=" + v[0] + ", longMarks=" + v[1]
                    + ", num=" + v[2] + ", enter=" + v[3] + ", smartPunct=" + v[4]
                    + ", fullwidth=" + v[5] + ", enPunct=" + v[6]
                    + ", autoPair=" + v[7] + ", physComplete=" + v[8]);
            return v;
        } catch (Throwable tr) {
            Log.w(TAG, "config restore failed: " + tr);
            return null;
        }
    }
}
