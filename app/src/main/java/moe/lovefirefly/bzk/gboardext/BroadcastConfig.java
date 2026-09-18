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

    /** 开发期：打印回传的状态位。 */
    static final boolean DEV_TRACE = false;

    private static final String TAG = "GboardExt";
    static final String ACTION = "moe.lovefirefly.bzk.gboardext.CONFIG";

    /**
     * **反向**通道（模块 → App）：把三个状态位回传给设置页显示「当前状态」。
     *
     * <p>为什么需要：状态位存在 Gboard 进程自己的 prefs 里（见 {@link #STATE_PREFS}），
     * App 读不到 —— 设置页只能显示"这个功能开没开"，显示不了"当前是哪一档"。
     * 搜狗那边是靠 provider 镜像（`mirrorState`），gb 的 provider 走不通（§15），
     * 所以用一条显式广播。广播**显式指定包名**，否则 Android 11+ 的包可见性会把它丢掉。
     */
    static final String ACTION_STATE = "moe.lovefirefly.bzk.gboardext.STATE";

    /** App 的包名：回传广播显式投递给它。 */
    static final String APP_PKG = "moe.lovefirefly.bzk.gboardext";

    /** 回传的状态位（与 {@link GboardState} 的三个一一对应）。 */
    static final String EXTRA_ST_FULL = "stateFullwidth";
    static final String EXTRA_ST_ENP = "stateEnPunct";
    static final String EXTRA_ST_PHYS = "statePhysComplete";

    /** 配置广播里的"请顺便回一条状态位"标记（设置页每次发配置都带）。 */
    static final String EXTRA_WANT_STATE = "wantState";

    /**
     * 把当前三个状态位回传给设置页。
     *
     * <p>由 {@link GboardState} 在状态位变化时调用（热键切换的那一刻）。
     * 显式指定包名投递；App 不在前台时广播被丢掉也没关系 —— 它下次进页面时
     * 会自己补发一条 {@link #ACTION} 配置，那时状态位照旧由热键写。
     */
    static void sendState(Context ctx, boolean fullwidth, boolean enPunct, boolean physComplete) {
        if (ctx == null) return;
        try {
            final android.content.Intent i = new android.content.Intent(ACTION_STATE);
            i.setPackage(APP_PKG);
            i.putExtra(EXTRA_ST_FULL, fullwidth);
            i.putExtra(EXTRA_ST_ENP, enPunct);
            i.putExtra(EXTRA_ST_PHYS, physComplete);
            ctx.sendBroadcast(i);
            if (DEV_TRACE) {
                Log.i(TAG, "state mirrored: full=" + fullwidth + " enP=" + enPunct
                        + " phys=" + physComplete);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "state mirror failed: " + tr);
        }
    }
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
    static final String EXTRA_OVERRIDE_ROTATION = "overrideRotation";
    static final String EXTRA_ROTATION_ORDER = "rotationOrder";

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
    private static final String K_OVERRIDE_ROTATION = "overrideRotation";
    private static final String K_ROTATION_ORDER = "rotationOrder";

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
                    // 设置页要求顺带回一条状态位 ⇒ 先把当前值发回去（配置还没应用也无所谓，
                    // 状态位与配置是两套东西）
                    if (intent.getBooleanExtra(EXTRA_WANT_STATE, false)) {
                        GboardState.mirrorNow();
                    }
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
                    String pairTable = intent.getStringExtra(EXTRA_PAIR_TABLE);
                    if (pairTable == null || pairTable.isEmpty()) {
                        pairTable = GboardPair.DEFAULT_TABLE;   // 没带就回退内置默认表
                    }
                    final boolean overrideRotation =
                            intent.getBooleanExtra(EXTRA_OVERRIDE_ROTATION, false);
                    final String rotationOrder = intent.getStringExtra(EXTRA_ROTATION_ORDER);
                    applyValues(strict, longMarks, smartNumbering, enter,
                            smartPunct, fullwidth, enPunct, autoPair, physComplete, pairTable,
                            overrideRotation, rotationOrder);
                    persist(c == null ? ctx : c, strict, longMarks, smartNumbering, enter,
                            smartPunct, fullwidth, enPunct, autoPair, physComplete, pairTable,
                            overrideRotation, rotationOrder);
                    Log.i(TAG, "config broadcast: strict=" + strict
                            + ", longMarks=" + longMarks + ", num=" + smartNumbering
                            + ", enter=" + enter + ", smartPunct=" + smartPunct
                            + ", fullwidth=" + fullwidth + ", enPunct=" + enPunct
                            + ", autoPair=" + autoPair + ", physComplete=" + physComplete
                            + ", overrideRotation=" + overrideRotation
                            + ", rotOrder=" + (rotationOrder == null ? "" : rotationOrder));
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
                (String) v[9], (Boolean) v[10], (String) v[11]);
    }

    private static void applyValues(boolean strict, boolean longMarks, boolean smartNumbering,
            boolean enter, boolean smartPunct, boolean fullwidth, boolean enPunct,
            boolean autoPair, boolean physComplete, String pairTable,
            boolean overrideRotation, String rotationOrder) {
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
        // 顺序轮转（P5）：漏过这里一次 ⇒ 表现是"开关打开、顺序也排了，但语言切换照旧 MRU"
        Rotation.setEnabled(overrideRotation);
        Rotation.setOrder(rotationOrder);
    }

    private static void persist(Context ctx, boolean strict, boolean longMarks,
            boolean smartNumbering, boolean enter, boolean smartPunct, boolean fullwidth,
            boolean enPunct, boolean autoPair, boolean physComplete, String pairTable,
            boolean overrideRotation, String rotationOrder) {
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
                    .putBoolean(K_OVERRIDE_ROTATION, overrideRotation)
                    .putString(K_ROTATION_ORDER, rotationOrder == null ? "" : rotationOrder)
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
                    sp.getString(K_PAIR_TABLE, GboardPair.DEFAULT_TABLE),
                    sp.getBoolean(K_OVERRIDE_ROTATION, false),
                    sp.getString(K_ROTATION_ORDER, "")};
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
