package moe.lovefirefly.bzk.gboardext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 广播来源校验：<b>只认自己人发的配置广播</b>。
 *
 * <p><b>为什么必须有这层</b>：接收器是<b>运行时注册在目标进程 （Gboard）</b>里的，
 * 为了让 Gboard（targetSdk 36）能跨应用投递，注册时必须声明 {@code RECEIVER_EXPORTED}
 * —— 这就等于把这个 action 变成公开入口：任何第三方 App 都能发同名显式广播，
 * 把 strict/自动配对/配对表这些配置改掉（= 改输入法行为）。这里是一个可写入面。
 *
 * <p>发送侧 {@code setPackage(TARGET_PKG)} <b>不构成防护</b>：它只约束"发给谁"，
 * 不约束"谁发的"。
 *
 * <p><b>两道防线</b>：
 * <ol>
 *   <li><b>签名级权限</b>（API 无关，<b>主防线</b>）：模块清单里定义
 *       {@link #PERMISSION}（{@code protectionLevel="signature"}），接收器注册时把它
 *       声明成 {@code broadcastPermission} ⇒ 系统在进入我们的代码之前就把非本签名
 *       发送方的广播丢掉（{@code dumpsys activity broadcasts} 里能看到
 *       {@code requiredPermission=…}）。</li>
 *   <li><b>发送方核对</b>（补充）：{@code BroadcastReceiver.getSentFromUid()} /
 *       {@code getSentFromPackage()} —— <b>这两个是 API 34 才有的公开 API</b>
 *       （本机 API 36 可用；API 27~33 拿不到发送方）。<b>判据是"有信息才判"</b>：
 *       平台没给发送方信息时放行（主防线已经拦过了），只有拿到信息且不匹配才拒绝 ——
 *       否则会把合法发送方误杀（实测踩过：拒了自家 App 的配置广播）。</li>
 * </ol>
 *
 * <p><b>反向通道例外</b>：状态位回传（Gboard → App）的发送方是<b>宿主自己的 uid</b>
 * （模块代码跑在 Gboard 进程里），它不可能持有我们的签名权限，所以那条只走
 * {@link #fromHost}（API 34+ 核对包名；信息缺失时放行 —— 那条通道只读三个状态位、
 * 改不了任何配置）。
 */
final class SenderCheck {

    private static final String TAG = "GboardExt";

    /** 签名级权限：只有与本模块同签名的 App 才能拿到并被允许投递配置广播。 */
    static final String PERMISSION = "moe.lovefirefly.bzk.gboardext.permission.CONFIG";

    /** 拒绝原因只报一次（按消息去重，不是全局一个开关 —— 否则第二个原因会被吞掉）。 */
    private static final Set<String> sWarned = ConcurrentHashMap.newKeySet();

    /** 第一次收到合法广播时把发送方信息打一次，用来确认第二道防线真的拿到了数据。 */
    private static volatile boolean sLoggedFirst;

    private SenderCheck() {
    }

    /**
     * 目标进程侧注册配置接收器：带签名级 {@code broadcastPermission}。
     *
     * <p>用 5 参重载（API 26+）同时保留"必须显式声明导出"这个 Android 14 的要求。
     */
    static void registerFromApp(Context ctx, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(r, f, PERMISSION, null);
        }
    }

    /** 目标进程侧：配置广播必须来自本模块 App。 */
    static boolean fromApp(Context ctx, BroadcastReceiver r, Intent intent) {
        return check(ctx, r, BroadcastConfig.APP_PKG, false, "config");
    }

    /** App 侧：状态位回传要来自宿主（Gboard 进程里的模块代码）。 */
    static boolean fromHost(Context ctx, BroadcastReceiver r, Intent intent) {
        return check(ctx, r, BridgeHook.TARGET_PKG, true, "state");
    }

    // ------------------------------------------------------------------ 内部

    private static boolean check(Context ctx, BroadcastReceiver r, String expected,
            boolean allowSelf, String what) {
        final String pkg;
        final int uid;
        try {
            pkg = r.getSentFromPackage();
            uid = r.getSentFromUid();
        } catch (Throwable tr) {
            // API < 34 没有这两个方法（或实现抛异常）：交给主防线（签名级权限）
            return pass(what, -1, null, "api<34");
        }
        if (expected.equals(pkg) || (uid >= 0 && hasPackage(ctx, uid, expected))
                || (allowSelf && uid >= 0 && uid == android.os.Process.myUid())) {
            return pass(what, uid, pkg, pkg == null ? "uid-match" : "pkg-match");
        }
        if (pkg == null && uid < 0) {
            // 平台没给发送方信息：主防线（签名级权限）已经保证只有本签名能投递，放行并记一笔。
            return pass(what, uid, pkg, "sender-unknown");
        }
        final String msg = what + " broadcast REJECTED: uid=" + uid + " pkg=" + pkg
                + "（期望 " + expected + "）";
        if (sWarned.add(msg)) Log.w(TAG, msg);
        return false;
    }

    private static boolean pass(String what, int uid, String pkg, String how) {
        if (!sLoggedFirst) {
            sLoggedFirst = true;
            Log.i(TAG, what + " broadcast accepted (" + how + "): uid=" + uid + " pkg=" + pkg);
        }
        return true;
    }

    private static boolean hasPackage(Context ctx, int uid, String pkg) {
        if (ctx == null) return false;
        try {
            final String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String p : pkgs) {
                if (pkg.equals(p)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
