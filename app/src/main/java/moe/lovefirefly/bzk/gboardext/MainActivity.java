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

    // 说明文案与「搜狗输入法联想版增强」对齐（同一套功能，两个组件说法一致）
    private static final String HINT_SHIFT_SPACE = "快捷键 Shift+Space";
    private static final String HINT_CTRL_DOT = "快捷键 Ctrl+.";
    private static final String HINT_CTRL_SHIFT_9 = "快捷键 Ctrl+Shift+9";

    private SharedPreferences prefs;
    /** 状态位镜像的落盘文件：模块回传后写这里，进页面时先读它。 */
    private static final String STATE_MIRROR = "gboardext_state_mirror";

    /** BetterZUIKey 的包名：严格模式要靠它接管语言切换，没装就禁用这个开关。 */
    private static final String BZK_PKG = "moe.lovefirefly.betterzuikey";

    private int pad;
    /** 三个状态位条目的「当前状态」行刷新器（收到回传时重跑）。 */
    private final java.util.List<Runnable> statusRefreshers = new java.util.ArrayList<>();
    private android.content.BroadcastReceiver stateReceiver;
    private MaterialSwitch strictSwitch;
    private TextView strictHint;
    private TextView autoRunTitle;
    private com.google.android.material.button.MaterialButton autoRunButton;
    private TextView autoRunHint;

    /** 右下角「刷新状态」悬浮键 + 它那个会转的图标（进页面和点击都要转）。 */
    private com.google.android.material.floatingactionbutton.FloatingActionButton refreshFab;
    private android.graphics.drawable.RotateDrawable refreshSpinIcon;
    /** 动画进行中：忽略连点，也免得进页面那次和点击撞在一起。 */
    private boolean refreshSpinning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        // P0 没有设置页 UI：用 `am start -n …/.MainActivity --es engineId mock` 切引擎
        // （空串 = 透传）。onResume 会把 prefs 里的值随配置广播发给 Gboard。
        try {
            final String engine = getIntent() == null
                    ? null : getIntent().getStringExtra("engineId");
            if (engine != null) {
                prefs.edit().putString(GboardConfig.KEY_ENGINE, engine).apply();
                android.util.Log.i("GboardExt", "engineId extra -> \"" + engine + "\"");
            }
        } catch (Throwable ignored) {
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(this);
        title.setText("Gboard 增强");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        title.setPadding(pad * 2, pad * 2, pad * 2, pad / 2);
        root.addView(title);

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
        autoRunRow.setPadding(0, 0, 0, 0);
        autoRunRow.addView(autoRunTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoRunRow.addView(autoRunButton);
        final LinearLayout autoRunBox = newItemBox(content);     // 与其它设置项同一套卡片
        autoRunBox.addView(autoRunRow);

        autoRunHint = new TextView(this);
        autoRunHint.setText("允许后可以更稳定同步配置。\n"
                + "注意：本插件无法自检，请自行跳转检查");
        autoRunHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        autoRunHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        autoRunBox.addView(autoRunHint);

        // —— 框架设置 ——
        // 自启动权限那张卡单独留在最上面（用户口径），这里才开始分区。
        addSectionHeader(content, "框架设置");
        addStrictSwitch(content);          // 只响应系统框架语言切换消息（裸行，不套卡片）

        // —— 符号设置：标点、全半角 ——
        addSectionHeader(content, "符号设置");

        addSwitch(content, "完整的 …… 和 ——",
                prefs.getBoolean(GboardConfig.KEY_LONG, true),
                "当输入 — 和 … 时，输出两个而不是一个",
                null, null, null,
                null,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_LONG, checked).apply());

        addSwitch(content, "智能中文标点",
                prefs.getBoolean(GboardConfig.KEY_SMART_PUNCT, true),
                "使用更合理的中文标点映射（+ - # 等按半角处理）",
                null, null, null,
                null,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_SMART_PUNCT, checked).apply());

        addSwitch(content, "全角模式",
                prefs.getBoolean(GboardConfig.KEY_FULLWIDTH, true),
                "允许在全角/半角之间切换。\n" + HINT_SHIFT_SPACE,
                BroadcastConfig.EXTRA_ST_FULL, "全角", "半角",
                GboardConfig.KEY_WANT_FULL,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_FULLWIDTH, checked).apply());

        addSwitch(content, "中英文标点",
                prefs.getBoolean(GboardConfig.KEY_EN_PUNCT, true),
                "允许中文模式下在中英标点之间切换。\n" + HINT_CTRL_DOT,
                BroadcastConfig.EXTRA_ST_ENP, "英文标点", "中文标点",
                GboardConfig.KEY_WANT_ENP,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_EN_PUNCT, checked).apply());

        // —— 补全设置：编号、匹配与补全 ——
        addSectionHeader(content, "补全设置");

        addSwitch(content, "智能编号",
                prefs.getBoolean(GboardConfig.KEY_NUMBER, true),
                "数字后面的 。和） 自动用半角 . 和 )，以方便输入 1.  2) 编号格式",
                null, null, null,
                null,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_NUMBER, checked).apply());

        // 引号/括号自动补全（Gboard 原生没有这个行为，由模块自己注入）
        addSwitch(content, "引号/括号自动补全（软键盘）",
                prefs.getBoolean(GboardConfig.KEY_AUTO_PAIR, true),
                "软键盘：关闭后打引号、括号不再自动补另一半（只出单个字符）。",
                null, null, null,
                null,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_AUTO_PAIR, checked).apply());

        addSwitch(content, "物理键盘自动补全",
                prefs.getBoolean(GboardConfig.KEY_PHYS_COMPLETE, false),
                "输入引号、括号时自动关闭并将光标移到中间\n" + HINT_CTRL_SHIFT_9,
                BroadcastConfig.EXTRA_ST_PHYS, "开", "关",
                GboardConfig.KEY_WANT_PHYS,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_PHYS_COMPLETE, checked).apply());

        // —— 杂项 ——
        addSectionHeader(content, "杂项");

        addSwitch(content, "中文态 Enter 不提交（保留原始拼音）",
                prefs.getBoolean(GboardConfig.KEY_ENTER, false),
                "中文态按 Enter 时，拼音串照常上屏，但整个输入框不再被提交。",
                null, null, null,
                null,
                checked -> prefs.edit().putBoolean(GboardConfig.KEY_ENTER, checked).apply());

        // ---- 语音输入（P1）：总开关 + 长按进配置页 ----
        addSectionHeader(content, "语音输入");
        addVoiceSwitch(content);

        // ---- 底部说明（与微信增强同款） ----
        // 顺带把末尾垫高：右下角那颗「刷新状态」FAB 是浮层，不留白会挡住最后几行。
        addGap(content, pad * 3);
        addHint(content, "切换后立即生效，无需重启 Gboard。");
        addGap(content, pad * 6);          // 给 FAB 让位

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);

        // 「刷新状态」：浮在滚动区右下角的 Material FAB（与搜狗 / WeType 同一套做法）。
        // 点一下 —— 以及每次进页面 —— 都转一圈并重推一次配置。
        refreshFab = new com.google.android.material.floatingactionbutton.FloatingActionButton(this);
        // 只转图标：把图标包进 RotateDrawable，动它的 level（0..10000 映射 0..360°），
        // 这样 FAB 本体（背景/阴影）保持不动。
        refreshSpinIcon = new android.graphics.drawable.RotateDrawable();
        refreshSpinIcon.setDrawable(getResources().getDrawable(android.R.drawable.ic_popup_sync));
        refreshSpinIcon.setLevel(0);
        refreshFab.setImageDrawable(refreshSpinIcon);
        refreshFab.setContentDescription("刷新状态");
        refreshFab.setTooltipText("刷新状态");
        // 持久阴影：FAB 本来有默认 elevation，但父层若裁剪就看不出 ⇒ 显式给一层 + 关掉裁剪
        refreshFab.setCompatElevation(6f * getResources().getDisplayMetrics().density);
        refreshFab.setOnClickListener(v -> spinRefreshFab());

        // 滚动区 + 悬浮刷新键同放一层 FrameLayout ⇒ 键浮在列表上方，不占布局高度
        final android.widget.FrameLayout scrollWrap = new android.widget.FrameLayout(this);
        scrollWrap.setClipChildren(false);        // 别裁掉 FAB 的阴影
        scrollWrap.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        final android.widget.FrameLayout.LayoutParams refreshLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        refreshLp.setMargins(0, 0, pad * 2, pad * 2);
        scrollWrap.addView(refreshFab, refreshLp);

        root.addView(scrollWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 状态位回传：模块在热键切换那一刻发一条广播过来 ⇒ 立刻刷新「当前状态」并落盘
        if (stateReceiver == null) {
            stateReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (i == null) return;
                    // 来源校验：这条反向通道的发送方是**宿主自己的 uid**（模块代码跑在
                    // Gboard 进程里），它不可能持有我们的签名级权限，所以只能核对包名
                    // （API 34+；低版本放行 —— 本接收器只读三个状态位，改不了配置）。
                    if (!SenderCheck.fromHost(c, this, i)) return;
                    getSharedPreferences(STATE_MIRROR, MODE_PRIVATE).edit()
                            .putBoolean(BroadcastConfig.EXTRA_ST_FULL,
                                    i.getBooleanExtra(BroadcastConfig.EXTRA_ST_FULL, false))
                            .putBoolean(BroadcastConfig.EXTRA_ST_ENP,
                                    i.getBooleanExtra(BroadcastConfig.EXTRA_ST_ENP, false))
                            .putBoolean(BroadcastConfig.EXTRA_ST_PHYS,
                                    i.getBooleanExtra(BroadcastConfig.EXTRA_ST_PHYS, true))
                            .apply();
                    refreshStatuses();
                }
            };
            final android.content.IntentFilter f =
                    new android.content.IntentFilter(BroadcastConfig.ACTION_STATE);
            // 必须是 EXPORTED：状态广播来自**另一个进程**（Gboard 里的模块），
            // Android 14+ 起 RECEIVER_NOT_EXPORTED 只收本应用/系统的广播 ⇒ 收不到。
            // 来源由 onReceive 里的 SenderCheck.fromHost 核对（API 34+ 认包名）；
            // 即便有人伪造也无害：这里只读三个状态位、写进"显示用"的镜像，改不了任何配置。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(stateReceiver, f);
            }
        }
        refreshStatuses();
        refreshStrict();
        refreshAutoRunState();
        // 进页面自动刷一次，并让右下角那个键转一圈
        spinRefreshFab();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (stateReceiver != null) {
            try { unregisterReceiver(stateReceiver); } catch (Throwable ignored) { }
            stateReceiver = null;      // 下次 onResume 重新注册
        }
    }

    /** 自启动状态：读 ZUI 记在 Settings.Secure 的那一项（读 secure settings 不需要权限）。 */
    /**
     * 刷新自启动那一行。
     *
     * <p><b>为什么不做"已获取 ⇒ 灰置"</b>（试过，做不到）：ZUI 把每个应用的自启动状态记在
     * **`com.zui.safecenter` 的私有 prefs**（`safecenter_main_preferences.xml` 里的
     * `<包名>|boot_start_up`），那是别的应用的数据目录，本 App 无权限读；
     * `Settings.Global/Secure` 里对应的 `|auto_run_state_change` 键在本机**根本不写**
     * （只有 su/browser 两个包有，我们包没有）；也不在系统的电池优化白名单里
     * （`PowerManager.isIgnoringBatteryOptimizations` 也读不到）。
     * 所以这里**不做检测**：按钮恒可点、恒显示「去获取」，由用户自己按需放行 ——
     * 显示一个永远为假的「已获取」比不显示更糟。
     */
    private void refreshAutoRunState() {
        if (autoRunButton == null) return;
        autoRunButton.setText("去获取");
        autoRunButton.setEnabled(true);
        autoRunButton.setOnClickListener(unused -> openAutoRunSettings());
    }

    /**
     * 去获取自启动权限。
     *
     * <p><b>ZUI 的设计</b>：自启动这一项挂在**应用信息页 →「权限」**下面。所以最短路径就是
     * 打开我们自己的应用信息页（"权限"入口可点，因为清单里声明了 POST_NOTIFICATIONS）；
     * 其次退到安全中心首页指路。ZUI 那个自启动页本身是 signature|privileged 权限保护的，
     * 第三方直达只会白页，所以不做直达尝试。
     */
    private void openAutoRunSettings() {
        // 1) 系统应用信息页：ZUI 的「权限 → 自启动」就在这里
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            android.widget.Toast.makeText(this,
                    "在本页「权限」里找到「自启动」并放行", android.widget.Toast.LENGTH_LONG).show();
            return;
        } catch (Throwable ignored) {
        }
        // 2) 兜底：只提示（不再拉起安全中心 —— 那需要 <queries> 可见性，会被 ZUI 标成"读取应用列表"）
        android.widget.Toast.makeText(this, "请在系统设置的「应用 → 权限」里允许自启动",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /**
     * 一个设置项 = **一张卡片**（规格与搜狗组件一致：12dp 圆角 / 1dp 描边 / 内边距 12,8,12,8，
     * ripple 动画，不做缩放），卡内左边一列「标题 + 说明」、右边无文字开关。
     *
     * @param hint      说明正文（可多行；空 = 不显示这一行）
     * @param stateKey  状态位在镜像里的键；非空时说明下方再显示一行「当前状态：…」
     * @param stateOn   状态位为真时的文案（如 {@code 全角}）；功能关掉时显示 {@code 功能已关闭}
     * @param stateOff  状态位为假时的文案（如 {@code 半角}）
     * @param wantKey   「长按应急切换」写的期望值键；非空时长按整卡可切状态位
     */
    /** 纯留白（微信增强那边叫 addGap，同一套做法）。 */
    private void addGap(LinearLayout parent, int px) {
        final View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px));
        parent.addView(v);
    }

    /** 底部说明文字（样式抄微信增强的 addHint：BodySmall + onSurfaceVariant）。 */
    private void addHint(LinearLayout parent, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, 0, 0, pad / 2);
        parent.addView(tv);
    }

    /**
     * 区块小标题。**只留这一个实现**：之前"中文态符号"是另一套（主题色 + 左右缩进 pad/2），
     * 与「语音输入」对不齐（用户口径：改成同一样式与左右位置）。
     */
    private void addSectionHeader(LinearLayout parent, String text) {        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(0, pad * 2, 0, pad / 2);
        parent.addView(tv);
    }

    /**
     * 「替换语音输入」总开关卡片（P1）。
     *
     * <p>与 {@link #addSwitch} 的唯一区别是<b>长按语义</b>：那边长按是"应急切状态位"，
     * 这边按用户口径是**长按卡片进子页面**（{@link VoiceEngineActivity}）。
     * 状态行显示当前选中的配置；没选就明说"退回原版 STT"。
     */
    private MaterialSwitch voiceSwitch;

    private void addVoiceSwitch(LinearLayout parent) {
        final MaterialSwitch sw = new MaterialSwitch(this);
        voiceSwitch = sw;
        sw.setPadding(pad / 2, 0, 0, 0);
        sw.setChecked(prefs.getBoolean(GboardConfig.KEY_VOICE_ENABLED, false));

        final TextView titleTv = new TextView(this);
        titleTv.setText("替换语音输入");
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        titleTv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        final TextView hint = new TextView(this);
        hint.setText("使用自定义API接管内置的语音输入，长按卡片进入配置页面");
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(0, 0, 0, pad / 4);

        final TextView state = new TextView(this);
        state.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        state.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        state.setPadding(0, 0, 0, pad / 4);
        statusRefreshers.add(() -> {
            state.setText(voiceStateText());
            // 子页面勾选配置时会把总开关自动打开 ⇒ 回主页要把开关的显示对齐，
            // 否则会出现"实际开着、界面显示关"（踩过 PRINCIPLE §13 那类问题）
            if (voiceSwitch != null
                    && voiceSwitch.isChecked() != prefs.getBoolean(
                            GboardConfig.KEY_VOICE_ENABLED, false)) {
                voiceSwitch.setChecked(prefs.getBoolean(GboardConfig.KEY_VOICE_ENABLED, false));
            }
        });

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(titleTv);
        texts.addView(hint);
        texts.addView(state);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        final LinearLayout box = newItemBox(parent);
        box.addView(row);

        sw.setOnCheckedChangeListener((CompoundButton v, boolean isChecked) -> {
            prefs.edit().putBoolean(GboardConfig.KEY_VOICE_ENABLED, isChecked).apply();
            sendConfig();
            refreshStatuses();
        });

        // 长按卡片（box 的父就是卡片本身）→ 进「自定义语音转文字」
        final android.view.View card = (android.view.View) box.getParent();
        card.setOnLongClickListener(v -> {
            try {
                startActivity(new android.content.Intent(this, VoiceEngineActivity.class));
            } catch (Throwable tr) {
                android.util.Log.w("GboardExt", "open voice page failed: " + tr);
            }
            return true;
        });
    }

    /** 状态行文案：当前配置文件 + 兜底提示。 */
    private String voiceStateText() {
        final VoiceProfiles.Profile p = VoiceProfiles.effective(this);
        if (p == null) return "当前配置：未勾选任何配置文件（退回原版 STT）";
        return "当前配置：" + p.label + "（" + p.id + "）";
    }

    private void addSwitch(LinearLayout parent, String text, boolean checked,                           String hint, String stateKey, String stateOn, String stateOff,
                           String wantKey,
                           final BoolSetter onChanged) {
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setPadding(pad / 2, 0, 0, 0);
        sw.setChecked(checked);        // ← 重构卡片时漏了这行：开关一律渲染成默认未选中（踩过）

        final TextView titleTv = new TextView(this);
        titleTv.setText(text);
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        titleTv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(titleTv);

        if (hint != null && !hint.isEmpty()) {
            final TextView tv = new TextView(this);
            tv.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodySmall);
            tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            tv.setPadding(0, 0, 0, pad / 4);
            tv.setText(hint);
            texts.addView(tv);
        }

        final TextView st = new TextView(this);
        if (stateKey != null) {
            st.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodySmall);
            st.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            st.setPadding(0, 0, 0, pad / 4);
            texts.addView(st);
            statusRefreshers.add(() -> st.setText("当前状态：" + (!isTargetImeActive()
                    ? "输入法未启用"
                    : (sw.isChecked()
                            ? (readStateMirror(stateKey) ? stateOn : stateOff)
                            : "功能已关闭"))));
        }

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        final LinearLayout box = newItemBox(parent);
        box.addView(row);

        sw.setOnCheckedChangeListener((CompoundButton v, boolean isChecked) -> {
            onChanged.set(isChecked);
            sendConfig();
            refreshStatuses();                 // 关掉功能 ⇒ 状态行改显示「功能已关闭」
        });

        // 长按应急切状态位：挂在**卡片**（box 的父）与开关上。
        // 别遍历子 View 去挂 —— 那会让子 View 变触摸目标、卡片不进 pressed、水波纹就没了（踩过）。
        if (wantKey != null) {
            final android.view.View.OnLongClickListener toggle = v -> {

                // 当前值以**我们自己写过的期望值**为准（第一次没有才回落到镜像）。
                // 不能只看镜像：镜像只在"模块回过传"时才更新 —— 刚进设置页、键盘还没动过时
                // 它是旧值，于是算出的 !now 可能等于真实状态 ⇒ 模块看没变化就不动作
                // ⇒ 表现是"长按没反应"（踩过）。
                final boolean now = prefs.contains(wantKey)
                        ? prefs.getBoolean(wantKey, false)
                        : readStateMirror(stateKey);
                prefs.edit().putBoolean(wantKey, !now)
                        .putLong(GboardConfig.KEY_WANT_SEQ, System.currentTimeMillis()).apply();
                sendConfig();                  // 立刻推给模块，否则要等下次配置周期
                android.widget.Toast.makeText(this,
                        text + "：" + (!now ? stateOn : stateOff),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            };
            ((android.view.View) box.getParent()).setOnLongClickListener(toggle);
            sw.setOnLongClickListener(toggle);
        }
    }

    /**
     * 严格模式：**裸行**（不套卡片，与搜狗一致），说明按检测结果动态给。
     *
     * <p>没装 BetterZUIKey 时开关**禁用**（alpha 0.45）—— 严格模式要靠 BZK 把语言切换
     * 消息发过来，没装则打开也没意义（文案与搜狗组件同口径，只把「搜狗OEM」换成「Gboard」）。
     */
    private void addStrictSwitch(LinearLayout parent) {
        strictSwitch = new MaterialSwitch(this);
        strictSwitch.setPadding(pad / 2, 0, 0, 0);
        strictSwitch.setChecked(prefs.getBoolean(GboardConfig.KEY_STRICT, false));

        final TextView titleTv = new TextView(this);
        titleTv.setText("只响应系统框架语言切换消息");
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        titleTv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        strictHint = new TextView(this);
        strictHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        strictHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        strictHint.setPadding(0, 0, 0, pad / 4);

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(titleTv);
        texts.addView(strictHint);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(strictSwitch);
        // 与其它设置项一样**套卡片**（原来这里是裸行，破坏了整页的一致性）
        final LinearLayout box = newItemBox(parent);
        box.addView(row);

        strictSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean(GboardConfig.KEY_STRICT, isChecked).apply();
            sendConfig();
        });
        refreshStrict();
    }

    /** 按"装没装 BZK"刷新严格模式的可用性与说明（进页面时调）。 */
    private void refreshStrict() {
        if (strictSwitch == null || strictHint == null) return;
        final boolean bzk = hasBetterZUIKey();
        strictSwitch.setEnabled(bzk);
        strictSwitch.setAlpha(bzk ? 1f : 0.45f);
        strictHint.setText(bzk
                ? "检测到BetterZUIKey，建议在它的\u201c输入法增强\u201d中为\u201cGboard\u201d"
                  + "启用\u201cframework\u201d模式，然后打开此开关，"
                  + "以让BetterZUIKey完全接管此选项"
                : "未检测到BetterZUIKey，建议安装以增强功能");
        // 没装 BZK ⇒ 勾选值不成立（与搜狗同一处理）
        strictSwitch.setOnCheckedChangeListener(null);
        strictSwitch.setChecked(prefs.getBoolean(GboardConfig.KEY_STRICT, false) && bzk);
        strictSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean(GboardConfig.KEY_STRICT, isChecked).apply();
            sendConfig();
        });
    }

    private boolean hasBetterZUIKey() {
        try {
            getPackageManager().getPackageInfo(BZK_PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读状态位镜像（模块回传后落在这里；读不到就用与 GboardState 一致的默认值）。 */
    private boolean readStateMirror(String key) {
        if (key != null && key.equals(BroadcastConfig.EXTRA_ST_PHYS)) {
            return getSharedPreferences(STATE_MIRROR, MODE_PRIVATE).getBoolean(key, true);
        }
        return getSharedPreferences(STATE_MIRROR, MODE_PRIVATE).getBoolean(key, false);
    }

    private void refreshStatuses() {
        for (Runnable r : statusRefreshers) r.run();
    }

    /** 一张设置卡（规格与搜狗组件完全一致），返回卡内可放内容的容器。 */
    private LinearLayout newItemBox(LinearLayout parent) {
        final com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad / 2, 0, 0);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4f);
        card.setCardElevation(0f);
        card.setStrokeWidth(Math.max(1, pad / 16));
        card.setStrokeColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant));
        // 动画风格与 BZK 一致：ripple（?attr/selectableItemBackground），不做缩放
        final android.util.TypedValue rippleTv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleTv, true);
        if (rippleTv.resourceId != 0) {
            card.setForeground(androidx.core.content.ContextCompat.getDrawable(this, rippleTv.resourceId));
        }
        card.setClickable(true);
        card.setFocusable(true);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2);
        card.addView(box);
        parent.addView(card);
        return box;
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
        sendConfig(true);
    }

    /**
     * @param wantState 是否顺便请模块回一条当前状态位（当前输入法不是 Gboard 时没必要要）
     */
    private void sendConfig(boolean wantState) {
        ConfigSender.send(this, prefs, wantState);
        ConfigRetry.schedule(this);
    }

    /**
     * 「刷新状态」：右下角那个键转一圈 + 重新推一次配置。
     *
     * <p>点键和<b>进页面</b>（{@link #onResume()}）都走这里 —— 进页面也转一圈，是为了让
     * 「刚进来就已经自动刷过一次」这件事看得见。
     */
    private void spinRefreshFab() {
        if (refreshFab == null || refreshSpinning) return;   // 动画期间忽略连点

        // 当前输入法不是 Gboard ⇒ 模块一项都不生效、状态位也永远要不回来：
        // 别转了、也别白要状态（配置照样推 + 照常排补播，免得设置丢了），把「输入法未启用」写上。
        if (!isTargetImeActive()) {
            refreshStatuses();
            sendConfig(false);
            return;
        }

        refreshSpinning = true;
        final android.animation.ObjectAnimator anim =
                android.animation.ObjectAnimator.ofInt(refreshSpinIcon, "level", 0, 10000);
        anim.setDuration(600);
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                refreshSpinning = false;
                refreshSpinIcon.setLevel(0);
            }
        });
        anim.start();
        sendConfig(true);
    }

    /**
     * 当前生效的输入法是不是 Gboard。
     *
     * <p>{@code Settings.Secure.DEFAULT_INPUT_METHOD} 是公开 secure setting，读它不需要权限
     * （本类已经在用同一招读自启动项）。不是 Gboard 时本模块一项都不会生效、状态位也拿不回来。
     *
     * <p>读不到时返回 {@code true}：宁可当成"在用"，也不要因为读不到就误报"未启用"。
     */
    private boolean isTargetImeActive() {
        try {
            final String cur = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            return cur == null || cur.startsWith(BridgeHook.TARGET_PKG);
        } catch (Throwable tr) {
            return true;
        }
    }
}
