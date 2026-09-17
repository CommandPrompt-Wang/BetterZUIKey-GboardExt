package moe.lovefirefly.bzk.gboardext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页：严格模式 + 中文态符号处理（完整的 …… 和 ——、顿号映射）。
 *
 * <p>配置写本 App 的 SharedPreferences，然后用<b>显式广播</b>立即推给 Gboard 进程里的模块
 * （provider 在 Gboard 上因包可见性走不通，见 ANALYSIS.md §15；广播是替代通道）。
 * 进页面（{@code onResume}）也会补发一次。
 */
public class MainActivity extends Activity {

    private static final String HINT_ON =
            "开着：Gboard 自己的「切语言 / 切布局」键会被拦掉，语言只由 BZK 的 Ctrl+Shift 驱动。\n"
            + "（换输入法不受影响；改完立即生效）";
    private static final String HINT_OFF = "关着：严格模式不介入，Gboard 自己切语言照常。";

    private static final String LONG_ON =
            "打开：下划线键出 ——、省略号出 ……（中文排版标准的完整形）";
    private static final String LONG_OFF = "关闭：只出一个 — 、一个 …（搜狗原生就是这样）";

    /** 顿号映射两档的说明，索引与 {@link SlashMap} 的模式号一致。 */
    private static final String[] DUNHAO_HINT = {
            "反斜杠键出 、（Gboard 原生），斜杠键还是 /",
            "两个键都出 、（斜杠键也改出顿号）",
    };

    private SharedPreferences prefs;
    private TextView strictHint;
    private TextView longHint;
    private TextView dunhaoHint;

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

        final TextView dunhaoTitle = new TextView(this);
        dunhaoTitle.setText("\n顿号映射");
        dunhaoTitle.setTextSize(16f);
        dunhaoTitle.setPadding(0, (int) (16 * d), 0, 0);
        box.addView(dunhaoTitle);

        final Spinner sp = new Spinner(this);
        final String[] items = {"\\（默认）", "全部"};
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        // 旧版本可能存过已删除的第三档（2）→ 归一到"全部"
        final int saved = prefs.getInt(GboardConfig.KEY_DUNHAO, SlashMap.MODE_BACKSLASH)
                == SlashMap.MODE_ALL ? SlashMap.MODE_ALL : SlashMap.MODE_BACKSLASH;
        sp.setSelection(saved);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View v,
                                       int pos, long id) {
                // Spinner 初始化时会自己回调一次，值没变就不当成用户操作
                if (pos == prefs.getInt(GboardConfig.KEY_DUNHAO, SlashMap.MODE_BACKSLASH)) return;
                prefs.edit().putInt(GboardConfig.KEY_DUNHAO, pos).apply();
                dunhaoHint.setText(DUNHAO_HINT[Math.max(0, Math.min(pos, 1))]);
                sendConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        box.addView(sp);

        dunhaoHint = new TextView(this);
        dunhaoHint.setText(DUNHAO_HINT[Math.max(0, Math.min(saved, 1))]);
        dunhaoHint.setTextSize(13f);
        box.addView(dunhaoHint);

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
            i.putExtra(BroadcastConfig.EXTRA_DUNHAO,
                    prefs.getInt(GboardConfig.KEY_DUNHAO, SlashMap.MODE_BACKSLASH));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
