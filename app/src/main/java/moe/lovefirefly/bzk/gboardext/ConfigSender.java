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

    /**
     * 发一条 + **顺便武装补播链**。
     *
     * <p>为什么必须有这个组合：接收器只活在 Gboard 进程里 —— 改设置那一刻它常常没在跑，
     * 单发一条就**永久丢掉**（P1 踩过：子页面只调了 {@link #send}，勾选框看起来生效了，
     * Gboard 那边其实一直没收到 ⇒ 表现是"mock 不工作"）。主设置页一直是这么配的，
     * 子页面漏了。
     */
    static void sendAndRetry(Context ctx) {
        send(ctx);
        ConfigRetry.schedule(ctx);
    }

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
        send(ctx, prefs, true);
    }

    /**
     * @param wantState 是否顺便请模块回一条当前状态位。当前生效的输入法不是 Gboard 时没必要要
     *                  ——模块不会生效，要了也不会回来（调用方见
     *                  {@code MainActivity#isTargetImeActive()}）。
     */
    static void send(Context ctx, SharedPreferences prefs, boolean wantState) {
        try {
            final Intent i = new Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.TARGET_PKG);
            // 默认值必须与**设置页**一致（严格模式：界面默认关）—— 否则会出现
            // "界面显示关、实际却是开的"（踩过，见 PRINCIPLE §13）
            i.putExtra(BroadcastConfig.EXTRA_STRICT,
                    prefs.getBoolean(GboardConfig.KEY_STRICT, false));
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
            // 默认值同上：必须与设置页一致（软键盘配对：界面默认**开**）
            i.putExtra(BroadcastConfig.EXTRA_AUTO_PAIR,
                    prefs.getBoolean(GboardConfig.KEY_AUTO_PAIR, true));
            i.putExtra(BroadcastConfig.EXTRA_PHYS_COMPLETE,
                    prefs.getBoolean(GboardConfig.KEY_PHYS_COMPLETE, false));
            i.putExtra(BroadcastConfig.EXTRA_PAIR_TABLE,
                    prefs.getString(GboardConfig.KEY_PAIR_TABLE, GboardPair.DEFAULT_TABLE));
            // 语音引擎：总开关 + 当前选中的那个配置（脚本正文随广播走，体积恒定）
            i.putExtra(BroadcastConfig.EXTRA_VOICE_ENABLED,
                    prefs.getBoolean(GboardConfig.KEY_VOICE_ENABLED, false));
            // 生效的配置 = 列表里第一个勾选的（勾选多个时按列表顺序）
            // 离线语音与配置文件**互斥**：选了离线模型就整体顶掉 profile（引擎 = 离线脚本）
            final VoiceModels.Model om = VoiceModels.find(VoiceModels.selected(ctx));
            final boolean offline = om != null && VoiceModels.ready(ctx, om);
            final VoiceProfiles.Profile prof = offline ? null : VoiceProfiles.effective(ctx);
            i.putExtra(BroadcastConfig.EXTRA_ENGINE, offline ? om.engineId
                    : (prof != null ? prof.id : ""));
            i.putExtra(BroadcastConfig.EXTRA_ENGINE_LABEL, offline ? om.label
                    : (prof != null ? prof.label : ""));
            // 离线引擎的脚本来自 assets（零网络、无参数）：目录名即脚本名
            final String offScript = offline
                    ? VoiceProfiles.readAsset(ctx, "engines/" + om.dir + ".js") : null;
            i.putExtra(BroadcastConfig.EXTRA_ENGINE_SCRIPT, offline
                    ? (offScript == null ? "" : offScript)
                    : (prof != null ? prof.script : ""));
            // engine.input.* 填的值（appid/token…）随配置一起下发（离线引擎无参数）
            i.putExtra(BroadcastConfig.EXTRA_ENGINE_CONFIG,
                    offline ? "{}" : VoiceProfiles.configJson(prof));
            // 域名白名单（Gboard 侧的 ctx.ws 会强制校验；离线引擎恒为空 = 一个域名都不许连）
            i.putExtra(BroadcastConfig.EXTRA_ENGINE_HOSTS,
                    offline ? "[]" : VoiceProfiles.hostsJson(prof));
            // 离线语音：选中的模型 + 版本（空 id = 没选/已删 ⇒ 宿主会清掉它那边的缓存）
            i.putExtra(BroadcastConfig.EXTRA_OFFLINE_MODEL, offline ? om.id : "");
            i.putExtra(BroadcastConfig.EXTRA_OFFLINE_VERSION,
                    offline ? VoiceModels.versionOf(om) : "");
            // 顺便请模块回一条当前状态位：设置页每次进来都会发配置，
            // 这样"先按键、后开 App"也能拿到最新状态（只靠热键那条广播会漏）
            if (wantState) {
                i.putExtra(BroadcastConfig.EXTRA_WANT_STATE, true);
            }
            // 「长按应急切换」的期望值：有才带上（没长按过就不带，模块也就不会动状态位）
            if (prefs.contains(GboardConfig.KEY_WANT_FULL)) {
                i.putExtra(BroadcastConfig.EXTRA_WANT_FULL,
                        prefs.getBoolean(GboardConfig.KEY_WANT_FULL, false));
            }
            if (prefs.contains(GboardConfig.KEY_WANT_ENP)) {
                i.putExtra(BroadcastConfig.EXTRA_WANT_ENP,
                        prefs.getBoolean(GboardConfig.KEY_WANT_ENP, false));
            }
            if (prefs.contains(GboardConfig.KEY_WANT_PHYS)) {
                i.putExtra(BroadcastConfig.EXTRA_WANT_PHYS,
                        prefs.getBoolean(GboardConfig.KEY_WANT_PHYS, false));
            }
            i.putExtra(BroadcastConfig.EXTRA_WANT_SEQ,
                    prefs.getLong(GboardConfig.KEY_WANT_SEQ, 0L));
            ctx.sendBroadcast(i);
            Log.i(TAG, "config sent");
        } catch (Throwable tr) {
            Log.w(TAG, "send failed: " + tr);
        }
    }
}
