package moe.lovefirefly.bzk.gboardext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页。目前只有严格模式一个开关 —— 配置写 SharedPreferences，由 {@code ConfigProvider}
 * 暴露给模块读取（每 2 秒轮询 + 签名比对，改完最多 2 秒生效）。
 */
public class MainActivity extends Activity {

    private static final String HINT_ON =
            "开着：Gboard 自己的「切语言 / 切布局」键会被拦掉，语言只由 BZK 的 Ctrl+Shift 驱动。\n"
            + "（换输入法不受影响；改完最多 2 秒生效）";
    private static final String HINT_OFF =
            "关着：严格模式不介入，Gboard 自己切语言照常。";

    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sp = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
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
        sw.setChecked(sp.getBoolean(GboardConfig.KEY_STRICT, true));
        sw.setOnCheckedChangeListener((v, checked) -> {
            sp.edit().putBoolean(GboardConfig.KEY_STRICT, checked).apply();
            hint.setText(checked ? HINT_ON : HINT_OFF);
            sendConfig(checked);
        });
        box.addView(sw);

        hint = new TextView(this);
        hint.setText(sw.isChecked() ? HINT_ON : HINT_OFF);
        hint.setTextSize(13f);
        box.addView(hint);

        final TextView foot = new TextView(this);
        foot.setText("\n符号全角→半角归一（中文态）：下一轮\n日志：adb shell logcat -s GboardExt");
        foot.setTextSize(13f);
        box.addView(foot);

        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：模块可能是在我们上次发送之后才注册的接收器，进页面时补发一次
        final SharedPreferences sp = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
        sendConfig(sp.getBoolean(GboardConfig.KEY_STRICT, true));
    }

    /** 显式广播给 Gboard 的包（只能发给可见的包，已在本 App 清单的 <queries> 里声明）。 */
    private void sendConfig(boolean strict) {
        final android.content.Intent i = new android.content.Intent(BroadcastConfig.ACTION);
        i.setPackage(BridgeHook.TARGET_PKG);
        i.putExtra(BroadcastConfig.EXTRA_STRICT, strict);
        sendBroadcast(i);
    }
}
