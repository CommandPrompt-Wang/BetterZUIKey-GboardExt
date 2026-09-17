package moe.lovefirefly.bzk.gboardext;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 第一轮只是个占位页：还没有可配置的功能，设置界面等定下功能面再做。 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final float d = getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (48 * d), (int) (24 * d), 0);
        final TextView tv = new TextView(this);
        tv.setText("Gboard 增强 · 第一轮\n\n"
                + "目标：" + BridgeHook.TARGET_PKG + "\n"
                + "当前只有只读探针（框架锚点），还没有任何开关。\n\n"
                + "静态分析结论见仓库根目录 ANALYSIS.md；\n"
                + "日志：adb shell logcat -s GboardExt");
        tv.setTextSize(14);
        tv.setGravity(Gravity.START);
        box.addView(tv);
        setContentView(box);
    }
}
