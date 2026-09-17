package moe.lovefirefly.bzk.gboardext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：严格模式开关 + 符号归一表。
 *
 * <p>配置写本 App 的 SharedPreferences，然后<b>立即</b>用显式广播推给 Gboard 进程里的模块
 * （provider 在 Gboard 上因包可见性走不通，见 ANALYSIS.md §15；广播是替代通道）。
 * 进页面（{@code onResume}）也补发一次：模块可能是在上次发送之后才注册接收器的。
 */
public class MainActivity extends Activity {

    private static final String HINT_ON =
            "开着：Gboard 自己的「切语言 / 切布局」键会被拦掉，语言只由 BZK 的 Ctrl+Shift 驱动。\n"
            + "（换输入法不受影响；改完立即生效）";
    private static final String HINT_OFF =
            "关着：严格模式不介入，Gboard 自己切语言照常。";

    private static final String NORM_HINT =
            "只在中文态生效：把本来就是 ASCII 的符号从全角拉回半角。\n"
            + "写法：每两个字符一对「全角→半角」，换行只当分组；留空 = 不处理。\n"
            + "日语不碰（它有完善的候选/组合框）；中文标点（，。！？；：、（）「」『』“”）也不碰。";

    private SharedPreferences prefs;
    private TextView hint;
    private TextView normSummary;

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
            hint.setText(checked ? HINT_ON : HINT_OFF);
            sendConfig();
        });
        box.addView(sw);

        hint = new TextView(this);
        hint.setText(sw.isChecked() ? HINT_ON : HINT_OFF);
        hint.setTextSize(13f);
        box.addView(hint);

        final TextView normTitle = new TextView(this);
        normTitle.setText("\n符号全角→半角归一（中文态）");
        normTitle.setTextSize(16f);
        normTitle.setPadding(0, (int) (16 * d), 0, 0);
        box.addView(normTitle);

        normSummary = new TextView(this);
        normSummary.setTextSize(13f);
        box.addView(normSummary);
        updateNormSummary();

        final TextView edit = new TextView(this);
        edit.setText("[ 编辑归一表 ]");
        edit.setTextSize(15f);
        edit.setPadding(0, (int) (12 * d), 0, 0);
        edit.setTextColor(0xFF2962FF);
        edit.setClickable(true);
        edit.setFocusable(true);
        edit.setOnClickListener(v -> showTableDialog());
        box.addView(edit);

        final TextView normHint = new TextView(this);
        normHint.setText(NORM_HINT);
        normHint.setTextSize(12f);
        normHint.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(normHint);

        final TextView foot = new TextView(this);
        foot.setText("\n日志：adb shell logcat -s GboardExt");
        foot.setTextSize(12f);
        box.addView(foot);

        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：模块可能是在我们上次发送之后才注册的接收器，进页面时补发一次
        sendConfig();
    }

    private void updateNormSummary() {
        final String tbl = prefs.getString(GboardConfig.KEY_TABLE, GboardConfig.DEFAULT_TABLE);
        final int pairs = tbl.replaceAll("\\s+", "").length() / 2;
        normSummary.setText(pairs == 0 ? "当前：不改（表已留空）" : "当前：" + pairs + " 对");
    }

    /** 归一表编辑器：多行文本，保存前校验"去掉空白后长度为偶数"（每两个字符一对）。 */
    private void showTableDialog() {
        final float d = getResources().getDisplayMetrics().density;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setHorizontallyScrolling(false);
        input.setText(prefs.getString(GboardConfig.KEY_TABLE, GboardConfig.DEFAULT_TABLE));
        input.setSelection(input.getText().length());

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        wrap.addView(input);

        final TextView reset = new TextView(this);
        reset.setText("[ 恢复默认 13 对 ]");
        reset.setTextColor(0xFF2962FF);
        reset.setTextSize(15f);
        reset.setPadding(0, (int) (10 * d), 0, 0);
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setOnClickListener(v -> {
            input.setText(GboardConfig.DEFAULT_TABLE);
            input.setSelection(input.getText().length());
        });
        wrap.addView(reset);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("符号归一表（全角→半角）")
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String raw = input.getText().toString().trim();
                    if (raw.replaceAll("\\s+", "").length() % 2 != 0) {
                        Toast.makeText(this, "长度必须是偶数（每两个字符一对）",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(GboardConfig.KEY_TABLE, raw).apply();
                    updateNormSummary();
                    sendConfig();
                    Toast.makeText(this, "已保存（立即生效）", Toast.LENGTH_SHORT).show();
                    dlg.dismiss();
                }));
        dlg.show();
    }

    /** 显式广播给 Gboard 的包（只能发给可见的包，已在本 App 清单的 <queries> 里声明）。 */
    private void sendConfig() {
        try {
            final android.content.Intent i = new android.content.Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.TARGET_PKG);
            i.putExtra(BroadcastConfig.EXTRA_STRICT, prefs.getBoolean(GboardConfig.KEY_STRICT, true));
            i.putExtra(BroadcastConfig.EXTRA_TABLE,
                    prefs.getString(GboardConfig.KEY_TABLE, GboardConfig.DEFAULT_TABLE));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
