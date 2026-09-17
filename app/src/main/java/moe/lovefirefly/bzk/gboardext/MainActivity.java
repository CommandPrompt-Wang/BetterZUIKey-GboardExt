package moe.lovefirefly.bzk.gboardext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页：严格模式 + 「完整的 …… 和 ——」。
 *
 * <p>配置写本 App 的 SharedPreferences，然后用<b>显式广播</b>立即推给 Gboard 进程里的模块
 * （provider 在 Gboard 上因包可见性走不通，见 ANALYSIS.md §15；广播是替代通道）。
 * 进页面（{@code onResume}）也会补发一次。
 *
 * <p>符号全角→半角归一是<b>内置</b>的（见 {@link SymbolNorm}），不给开关也不给编辑框。
 * 「顿号映射」曾经做过三档，但 Gboard 在提交之前就把"哪个物理键"的信息丢了
 * （ANALYSIS.md §22：三条路径在 commitText / 漏斗 / 栈 各层完全相同），无法精确实现，
 * 已整体删除 —— 以后若去 hook 自绘键盘的触摸分发再回来做。
 */
public class MainActivity extends Activity {

    private static final String HINT_ON =
            "开着：Gboard 自己的「切语言 / 切布局」键会被拦掉，语言只由 BZK 的 Ctrl+Shift 驱动。\n"
            + "（换输入法不受影响；改完立即生效）";
    private static final String HINT_OFF = "关着：严格模式不介入，Gboard 自己切语言照常。";

    private static final String LONG_ON =
            "打开：下划线键出 ——、省略号出 ……（中文排版标准的完整形）";
    private static final String LONG_OFF = "关闭：只出一个 — 、一个 …（搜狗原生就是这样）";

    private SharedPreferences prefs;
    private TextView strictHint;
    private TextView longHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
        final float d = getResources().getDisplayMetrics().density;

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (48 * d), (int) (24 * d), 0);

        final TextView title = new TextView(this);
        title.setText("Gboard 增强");
        title.setTextSize(22f);
        box.addView(title);

        final Switch sw = new Switch(this);
        sw.setText("严格模式：语言只由框架 / BZK 决定");
        sw.setChecked(prefs.getBoolean(GboardConfig.KEY_STRICT, true));
        sw.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(GboardConfig.KEY_STRICT, checked).apply();
            strictHint.setText(checked ? HINT_ON : HINT_OFF);
            sendConfig();
        });
        box.addView(sw);

        strictHint = new TextView(this);
        strictHint.setText(sw.isChecked() ? HINT_ON : HINT_OFF);
        strictHint.setTextSize(13f);
        box.addView(strictHint);

        final TextView sec = new TextView(this);
        sec.setText("\n中文态符号");
        sec.setTextSize(16f);
        sec.setPadding(0, (int) (16 * d), 0, 0);
        box.addView(sec);

        final Switch lw = new Switch(this);
        lw.setText("完整的 …… 和 ——");
        lw.setChecked(prefs.getBoolean(GboardConfig.KEY_LONG, true));
        lw.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(GboardConfig.KEY_LONG, checked).apply();
            longHint.setText(checked ? LONG_ON : LONG_OFF);
            sendConfig();
        });
        box.addView(lw);

        longHint = new TextView(this);
        longHint.setText(lw.isChecked() ? LONG_ON : LONG_OFF);
        longHint.setTextSize(13f);
        box.addView(longHint);

        final TextView note = new TextView(this);
        note.setText("另外内置：｛｝／｜＠＃％＆＊～－ 拉回半角、反引号键出 ·（姓名圆点）。"
                + "\n以上都只在中文态生效，日语一律不碰。");
        note.setTextSize(13f);
        note.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(note);

        final TextView foot = new TextView(this);
        foot.setText("\n日志：adb shell logcat -s GboardExt");
        foot.setTextSize(12f);
        box.addView(foot);

        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendConfig();
    }

    /** 显式广播给 Gboard 的包（只能发给可见的包，已在本 App 清单的 <queries> 里声明）。 */
    private void sendConfig() {
        try {
            final android.content.Intent i = new android.content.Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.TARGET_PKG);
            i.putExtra(BroadcastConfig.EXTRA_STRICT, prefs.getBoolean(GboardConfig.KEY_STRICT, true));
            i.putExtra(BroadcastConfig.EXTRA_LONG, prefs.getBoolean(GboardConfig.KEY_LONG, true));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
