package moe.lovefirefly.bzk.gboardext;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设置页：严格模式 + 中文态符号处理。
 *
 * <p>版式与 {@code SogouOEMExt} 保持一致：Material 3 主题（{@code themes.xml} 里已同款）、
 * 标题用 {@code HeadlineSmall}、说明用 {@code BodySmall} + {@code colorOnSurfaceVariant}、
 * 开关用 {@link MaterialSwitch}、根视图走 {@link #applyInsets} 处理系统栏。
 *
 * <p>配置写本 App 的 SharedPreferences，然后用<b>显式广播</b>立即推给 Gboard 进程里的模块
 * （provider 在 Gboard 上因包可见性走不通，见 ANALYSIS.md §15；广播是替代通道）。
 * 进页面（{@code onResume}）也会补发一次。
 *
 * <p>符号全角→半角归一是内置的（见 {@link SymbolNorm}）。
 * 「顿号映射」曾做过三档，但 Gboard 提交前就丢了"哪个物理键"的信息（ANALYSIS.md §22），
 * 无法精确实现，已整体删除。
 */
public class MainActivity extends AppCompatActivity {

    private static final String HINT_ON =
            "开着：Gboard 自己的「切语言 / 切布局」键会被拦掉，语言只由 BZK 的 Ctrl+Shift 驱动。\n"
            + "（换输入法不受影响；改完立即生效）";
    private static final String HINT_OFF = "关着：严格模式不介入，Gboard 自己切语言照常。";

    private static final String LONG_ON =
            "打开：下划线键出 ——、省略号出 ……（中文排版标准的完整形）";
    private static final String LONG_OFF = "关闭：只出一个 — 、一个 …（搜狗原生就是这样）";

    private static final String NUM_ON =
            "打开：数字后面紧跟的 。/） 自动用半角 —— 1.  2)  这种编号";
    private static final String NUM_OFF = "关闭：数字后面照常出 。/）";

    private SharedPreferences prefs;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(this);
        title.setText("Gboard 增强");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        title.setPadding(pad * 2, pad * 2, pad * 2, pad / 2);
        root.addView(title);

        final TextView sub = new TextView(this);
        sub.setText("语言只管切换，符号只管中文 —— 日语一律不碰");
        sub.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        sub.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setPadding(pad * 2, 0, pad * 2, pad / 2);
        root.addView(sub);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad * 2, 0, pad * 2, pad * 2);

        // —— 严格模式 ——
        addSwitch(content, "严格模式：语言只由框架 / BZK 决定",
                prefs.getBoolean(GboardConfig.KEY_STRICT, true),
                HINT_ON, HINT_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_STRICT, checked).apply());

        // —— 中文态符号 ——
        final TextView sec = new TextView(this);
        sec.setText("中文态符号");
        sec.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        sec.setPadding(0, pad, 0, 0);
        content.addView(sec);

        addSwitch(content, "完整的 …… 和 ——",
                prefs.getBoolean(GboardConfig.KEY_LONG, true),
                LONG_ON, LONG_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_LONG, checked).apply());

        addSwitch(content, "智能编号",
                prefs.getBoolean(GboardConfig.KEY_NUMBER, true),
                NUM_ON, NUM_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_NUMBER, checked).apply());

        final TextView note = new TextView(this);
        note.setText("另外内置：｛｝／｜＠＃％＆＊～－＋＝＾＄ 拉回半角、反引号键出 ·（姓名圆点）。\n"
                + "以上都只在中文态生效。");
        note.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        note.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        note.setPadding(0, pad, 0, 0);
        content.addView(note);

        final TextView foot = new TextView(this);
        foot.setText("日志：adb shell logcat -s GboardExt");
        foot.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        foot.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        foot.setPadding(0, pad, 0, 0);
        content.addView(foot);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendConfig();
    }

    /** 一个开关 + 一行说明（与隔壁 SogouOEMExt 的 addSwitch 同款）。 */
    private void addSwitch(LinearLayout parent, String text, boolean checked,
                           String hintOn, String hintOff,
                           final BoolSetter onChanged) {
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setText(text);
        sw.setPadding(0, pad / 2, 0, pad / 4);
        sw.setChecked(checked);
        parent.addView(sw);

        final TextView hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setText(checked ? hintOn : hintOff);
        hint.setPadding(0, 0, 0, pad / 4);
        parent.addView(hint);

        sw.setOnCheckedChangeListener((CompoundButton v, boolean isChecked) -> {
            hint.setText(isChecked ? hintOn : hintOff);
            onChanged.set(isChecked);
            sendConfig();
        });
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private int themeColor(int attrRes) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return androidx.core.content.ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }

    /** Android 15+ 强制 edge-to-edge：把系统栏高度补成内边距（与隔壁同一份实现）。 */
    static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                final android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    /** 显式广播给 Gboard 的包（只能发给可见的包，已在本 App 清单的 <queries> 里声明）。 */
    private void sendConfig() {
        try {
            final android.content.Intent i = new android.content.Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.TARGET_PKG);
            i.putExtra(BroadcastConfig.EXTRA_STRICT, prefs.getBoolean(GboardConfig.KEY_STRICT, true));
            i.putExtra(BroadcastConfig.EXTRA_LONG, prefs.getBoolean(GboardConfig.KEY_LONG, true));
            i.putExtra(BroadcastConfig.EXTRA_NUMBER,
                    prefs.getBoolean(GboardConfig.KEY_NUMBER, true));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
