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

    private static final String PUNCT_ON = "打开：使用更合理的中文标点映射（反引号出 ·、下划线出 —/—— 等）";
    private static final String PUNCT_OFF = "关闭：符号原样（只剩下面「全角模式」那层宽度归一）";

    private static final String FULL_ON =
            "打开：可用 Shift+Space 在 全角/半角 之间切（状态自动记住）。"
            + "当前是半角时把 Gboard 全角化的符号拉回半角（－ → -、＋ → + 等）";
    private static final String FULL_OFF = "关闭：恒半角，Shift+Space 不起作用";

    private static final String ENP_ON =
            "打开：可用 Ctrl+. 在 中文标点/英文标点 之间切（状态自动记住）；"
            + "英文标点状态下中文标点会还原成 ASCII";
    private static final String ENP_OFF = "关闭：恒中文标点，Ctrl+. 不起作用";

    private static final String ENTER_ON =
            "打开：拼音栏有字时按 Enter 只把原始拼音上屏，不再把输入框提交出去"
            + "（相当于自动按 Shift+Enter）；拼音栏空着时 Enter 照常发送/换行";
    private static final String ENTER_OFF =
            "关闭：按 Enter 会照常触发输入框的提交（在\"回车即提交\"的搜索框/消息栏里会直接把内容发出去）";

    private SharedPreferences prefs;
    private int pad;
    private TextView autoRunTitle;
    private com.google.android.material.button.MaterialButton autoRunButton;
    private TextView autoRunHint;

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

        // —— 自启动权限（放最上面：它是"设置能不能可靠送到"的前提）——
        // 判定键：ZUI 把每个应用的自启动状态记在 Settings.Secure 的
        // "<包名>|auto_run_state_change"（1=已放行；没有这一项就是默认/未放行）。
        // 背景：本机自启动管理会拦"从广播冷启动我方进程"，被拦时配置补播送不出去。
        autoRunTitle = new TextView(this);
        autoRunTitle.setText("自启动权限");
        autoRunTitle.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        autoRunTitle.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        // 右对齐的圆角按钮（Material 3 描边按钮 + 胶囊圆角）
        autoRunButton = new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        autoRunButton.setText("去获取");
        autoRunButton.setAllCaps(false);
        autoRunButton.setMinWidth(0);
        autoRunButton.setMinimumWidth(0);
        // 圆角给足 ⇒ 被钳到高度一半，就是胶囊形
        autoRunButton.setCornerRadius((int) (40 * getResources().getDisplayMetrics().density));
        autoRunButton.setOnClickListener(v -> openAutoRunSettings());

        final LinearLayout autoRunRow = new LinearLayout(this);
        autoRunRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRunRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        autoRunRow.setPadding(0, pad, 0, 0);
        autoRunRow.addView(autoRunTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoRunRow.addView(autoRunButton);
        content.addView(autoRunRow);

        autoRunHint = new TextView(this);
        autoRunHint.setText("允许后，改完设置即使 Gboard 没在跑也能可靠送达（本机自启动管理会拦后台启动）。"
                + "ZUI 的自启动页只对系统应用开放，所以按钮会带你到安全中心首页，"
                + "点\"权限管理 → 自启动\"即可；不允许时可用\"开着键盘打开一次本应用\"兜底。");
        autoRunHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        autoRunHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        content.addView(autoRunHint);

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

        addSwitch(content, "智能中文标点",
                prefs.getBoolean(GboardConfig.KEY_SMART_PUNCT, true),
                PUNCT_ON, PUNCT_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_SMART_PUNCT, checked).apply());

        addSwitch(content, "全角模式",
                prefs.getBoolean(GboardConfig.KEY_FULLWIDTH, true),
                FULL_ON, FULL_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_FULLWIDTH, checked).apply());

        addSwitch(content, "中英文标点",
                prefs.getBoolean(GboardConfig.KEY_EN_PUNCT, true),
                ENP_ON, ENP_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_EN_PUNCT, checked).apply());

        addSwitch(content, "智能编号",
                prefs.getBoolean(GboardConfig.KEY_NUMBER, true),
                NUM_ON, NUM_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_NUMBER, checked).apply());

        addSwitch(content, "中文态 Enter 不提交（保留原始拼音）",
                prefs.getBoolean(GboardConfig.KEY_ENTER, false),
                ENTER_ON, ENTER_OFF,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_ENTER, checked).apply());

        final TextView note = new TextView(this);
        note.setText("半角那层是区间规则（全角 ASCII 区整段拉回半角，只放过中文标点），"
                + "所以 ＋＝＾＄ 这类不会再漏。\n"
                + "语义层：反引号键出 ·（姓名圆点）、下划线键出 —、省略号按开关出 ……。\n"
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
        refreshAutoRunState();
        sendConfig();
    }

    /** 自启动状态：读 ZUI 记在 Settings.Secure 的那一项（读 secure settings 不需要权限）。 */
    private void refreshAutoRunState() {
        if (autoRunButton == null) return;
        boolean ok = false;
        try {
            final String v = android.provider.Settings.Secure.getString(getContentResolver(),
                    getPackageName() + "|auto_run_state_change");
            ok = "1".equals(v);
        } catch (Throwable ignored) {
        }
        // 已获取 ⇒ 灰掉（disabled 的 MaterialButton 自带灰化）
        autoRunButton.setText(ok ? "已获取" : "去获取");
        autoRunButton.setEnabled(!ok);
        autoRunButton.setOnClickListener(ok ? null : v -> openAutoRunSettings());
    }

    /**
     * 去获取自启动权限。
     *
     * <p><b>实测结论</b>：ZUI 的自启动页 `PerfWhitelistActivity` 与自启动白名单 provider 都被
     * `com.zui.safecenter.permission.CLEANMGR_STARTUP`（**signature|privileged**）保护 ——
     * 第三方应用拿不到这个权限（清单里声明也没用），硬跳过去只会看到**空白页**。
     * 所以这里：拿到权限才直接跳那个页；否则退到**安全中心首页**并用 Toast 指路。
     */
    private void openAutoRunSettings() {
        // 1) 有权限（系统/特权应用才有）⇒ 直接跳自启动页
        try {
            if (checkSelfPermission("com.zui.safecenter.permission.CLEANMGR_STARTUP")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                final android.content.Intent i = new android.content.Intent(
                        "com.lenovo.safecenter.action.START_PERFWHITELISTACTIVITY");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 2) 退到安全中心首页（launcher 入口，一定打得开）并指路
        try {
            final android.content.Intent home = getPackageManager()
                    .getLaunchIntentForPackage("com.zui.safecenter");
            if (home != null) {
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                android.widget.Toast.makeText(this,
                        "在安全中心里：权限管理 → 自启动，放行「Gboard 增强」",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable ignored) {
        }
        // 3) 兜底：系统的应用详情页
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable tr) {
            android.widget.Toast.makeText(this, "找不到自启动设置页，请在安全中心里手动允许",
                    android.widget.Toast.LENGTH_LONG).show();
        }
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

    /**
     * 配置变更：立刻广播一次 + 排一条**补播链**。
     *
     * <p>为什么要补播：接收器在 Gboard 进程里运行时注册，进程不在时广播直接丢（实测踩过）。
     * 详见 {@link ConfigSender} / {@link ConfigRetry}。
     */
    private void sendConfig() {
        ConfigSender.send(this, prefs);
        ConfigRetry.schedule(this);
    }
}
