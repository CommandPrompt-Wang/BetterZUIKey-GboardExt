package moe.lovefirefly.bzk.gboardext;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

/**
 * 「自定义语音转文字」子页面（P1）。
 *
 * <p><b>UI 逐字抄 BZK 的「输入法适配管理」</b>（用户口径）：结构照 {@code activity_ime_adapter.xml}
 * （Toolbar + 区块标题 + 卡片列表 + 「＋ 添加…」入口 + 分隔线 + 说明 + 底部两个文字入口），
 * 行布局照 {@code item_ime_row.xml}（checkbox + 两行文字 + 右下角红色「删除」）。
 *
 * <p><b>语义</b>：勾选 = 启用，用 checkbox 但**互斥** —— 同一时刻只有「一个或零个」被勾选
 * （用户口径：不是多选）；全都不勾 ⇒ 退回原版 STT。
 */
public class VoiceEngineActivity extends AppCompatActivity {

    private static final String ISSUE_URL =
            "https://github.com/CommandPrompt-Wang/BetterZUIKey-GboardExt/issues";

    private LinearLayout listProfiles;
    private LinearLayout listModels;
    private LayoutInflater inflater;

    /** 下载中每 500ms 刷一次进度（就绪后自动停）。 */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // 先刷一次：状态可能刚好从"下载中"变成"就绪"，这一下不刷就永远停在 99%（踩过）
            renderModels();
            if (anyDownloading()) ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_engine);
        inflater = LayoutInflater.from(this);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        listProfiles = findViewById(R.id.list_profiles);
        listModels = findViewById(R.id.list_models);
        findViewById(R.id.btn_add_profile).setOnClickListener(v ->
                startActivity(new Intent(this, VoiceEngineAddActivity.class)));

        findViewById(R.id.tv_restore_builtins).setOnClickListener(v -> {
            VoiceProfiles.restoreBuiltins(this);
            render();
            ConfigSender.sendAndRetry(this);
            Toast.makeText(this, "已恢复内置配置", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.tv_force_update).setOnClickListener(v -> {
            VoiceProfiles.forceUpdate(this);
            render();
            ConfigSender.sendAndRetry(this);
            Toast.makeText(this, "已刷新内置脚本并重新下发", Toast.LENGTH_SHORT).show();
        });

        render();
        setupIssueLink();
        ui.post(tick);
    }

    /**
     * 把说明第 2 条里的「提交 Issue」做成**可点的链接**（用户口径：不要写成
     * 「提交 Issue（https://…）」那种把地址摊在正文里的样子）。
     */
    private void setupIssueLink() {
        final TextView tv = findViewById(R.id.tv_note_issue);
        if (tv == null) return;
        final String full = tv.getText().toString();
        final String key = "提交 Issue";
        final int at = full.indexOf(key);
        if (at < 0) return;
        final android.text.SpannableString ss = new android.text.SpannableString(full);
        ss.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(@androidx.annotation.NonNull View widget) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(ISSUE_URL)));
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(themeColor(com.google.android.material.R.attr.colorPrimary));
                ds.setUnderlineText(true);
            }
        }, at, at + key.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tv.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();          // 从「添加配置文件」回来要立刻看到新条目；下载状态可能也变了
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    // ------------------------------------------------------------------ 离线语音（模型）

    private boolean anyDownloading() {
        for (VoiceModels.Model m : VoiceModels.all()) {
            if (VoiceModels.STATE_DOWNLOADING.equals(VoiceModels.state(this, m.id))) return true;
        }
        return false;
    }

    /**
     * 渲染「离线语音」两块模型卡片。
     *
     * <p>规则（用户口径）：
     * <ul>
     *   <li>勾选与上面的「选择配置文件」**互斥**（{@link VoiceModels#setSelected} 会清掉上面的勾选）；</li>
     *   <li>**没下载就不能被选择**（checkbox 禁用）；</li>
     *   <li>下载完成后右侧「下载」自动变「删除」；下载中显示百分比、可取消。</li>
     * </ul>
     */
    private void renderModels() {
        if (listModels == null) return;
        listModels.removeAllViews();
        for (final VoiceModels.Model m : VoiceModels.all()) {
            final View row = inflater.inflate(R.layout.item_model_row, listModels, false);
            final CheckBox cb = row.findViewById(R.id.cb_model);
            final TextView name = row.findViewById(R.id.tv_model_name);
            final TextView info = row.findViewById(R.id.tv_model_info);
            final TextView prog = row.findViewById(R.id.tv_model_progress);
            final TextView action = row.findViewById(R.id.tv_model_action);

            final boolean ready = VoiceModels.ready(this, m);
            final boolean downloading =
                    VoiceModels.STATE_DOWNLOADING.equals(VoiceModels.state(this, m.id));

            name.setText(m.label);
            info.setText("大小：" + m.sizeText() + "　" + m.note);
            cb.setChecked(m.id.equals(VoiceModels.selected(this)));
            cb.setEnabled(ready);
            cb.setAlpha(ready ? 1f : 0.4f);
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (!VoiceModels.ready(this, m)) return;      // 未下载不给选
                VoiceModels.setSelected(this, m.id, checked);
                if (checked) setVoiceEnabled(this, true);
                ConfigSender.sendAndRetry(this);
                render();                                      // 把上面配置文件的勾选刷新掉
            });

            action.setText(ready ? "删除" : (downloading ? "取消" : "下载"));
            action.setOnClickListener(v -> {
                if (VoiceModels.ready(this, m)) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("删除 " + m.label + "？")
                            .setMessage("将释放 " + m.sizeText() + " 空间；删除后该项不可选。")
                            .setPositiveButton("删除", (d, w) -> {
                                VoiceModels.delete(this, m);
                                // 告诉宿主：这个模型的缓存也删掉（它那边留着一份运行时副本）
                                ConfigSender.sendAndRetry(this);
                                render();
                                Toast.makeText(this, "已删除 " + m.label, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } else if (VoiceModels.STATE_DOWNLOADING.equals(VoiceModels.state(this, m.id))) {
                    final android.content.Intent i = new android.content.Intent(this,
                            ModelDownloadService.class)
                            .setAction(ModelDownloadService.ACTION_CANCEL);
                    startService(i);
                    Toast.makeText(this, "已取消（已下载部分会保留，可续传）",
                            Toast.LENGTH_SHORT).show();
                    renderModels();
                } else {
                    askNotificationThenDownload(m);
                }
            });

            if (downloading) {
                final int pct = Math.max(0, VoiceModels.progress(this, m.id));
                prog.setVisibility(View.VISIBLE);
                prog.setText("下载中 " + pct + "%");
            } else {
                prog.setVisibility(View.GONE);
            }
            listModels.addView(row);
        }
    }

    /** 下载前问一下通知权限（Gboard 自己没声明它，进度条只能我们发），拒了也能下。 */
    private void askNotificationThenDownload(VoiceModels.Model m) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Throwable ignored) {
        }
        ModelDownloadService.start(this, m.id);
        Toast.makeText(this, "开始下载 " + m.label + "（" + m.sizeText() + "）",
                Toast.LENGTH_SHORT).show();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 500);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        renderModels();
        listProfiles.removeAllViews();
        final List<VoiceProfiles.Profile> list = VoiceProfiles.load(this);
        for (VoiceProfiles.Profile p : list) {
            final View row = inflater.inflate(R.layout.item_voice_row, listProfiles, false);
            final MaterialCardView card = (MaterialCardView) row;
            final CheckBox cb = row.findViewById(R.id.cb_enabled);
            final TextView name = row.findViewById(R.id.tv_name);
            final TextView idTv = row.findViewById(R.id.tv_id);

            name.setText(p.label == null || p.label.isEmpty() ? p.id : p.label);
            // 改过的内置项不会被「随模块更新」覆盖 ⇒ 标出来，省得日后困惑"为什么这个脚本没更新"
            idTv.setText(VoiceProfiles.modified(p) ? p.id + "（已修改）" : p.id);
            cb.setChecked(p.enabled);

            // 勾选 = 启用（互斥：勾上它会取消其它；也允许全部取消 ⇒ 退回原版 STT）
            // 注意：**只有 checkbox 改变勾选**，点卡片不做这件事（BZK 同款，别把两者混在一起）
            cb.setOnCheckedChangeListener((v, checked) -> {
                VoiceProfiles.setEnabled(VoiceEngineActivity.this, p.id, checked);
                // "勾选以启用"必须真的启用：总开关在主页那张卡片上，用户没想到还要去开一次
                // （踩过：填完 key、勾上配置，Alt+D 毫无反应 —— 因为 voiceEnabled 还是 false）
                if (checked) setVoiceEnabled(VoiceEngineActivity.this, true);
                ConfigSender.sendAndRetry(VoiceEngineActivity.this);
                render();          // 把被自动取消的那些勾选框刷新掉
            });
            // 单击卡片 = 打开"engine.input.*"表单（没有可填项就提示一下）
            card.setOnClickListener(v -> showInputDialog(p));
            // 长按卡片 = 复制代码（BZK 的长按脉冲反馈）
            card.setOnLongClickListener(v -> {
                pulseLongPress(card);
                copy(p.script);
                Toast.makeText(this, "已复制 " + p.id + " 的代码", Toast.LENGTH_SHORT).show();
                return true;
            });
            attachPressFeedback(card);
            row.findViewById(R.id.tv_delete).setOnClickListener(v -> {
                VoiceProfiles.delete(VoiceEngineActivity.this, p.id);
                render();
                ConfigSender.sendAndRetry(VoiceEngineActivity.this);
                Toast.makeText(this, "已删除 " + p.id, Toast.LENGTH_SHORT).show();
            });

            listProfiles.addView(row);
        }
    }

    // ------------------------------------------------------------------ 杂项

    static void setVoiceEnabled(Context c, boolean on) {
        VoiceProfiles.prefs(c).edit().putBoolean(GboardConfig.KEY_VOICE_ENABLED, on).apply();
    }

    static boolean isVoiceEnabled(Context c) {
        return VoiceProfiles.prefs(c).getBoolean(GboardConfig.KEY_VOICE_ENABLED, false);
    }

    /**
     * 单击卡片弹出的配置表单：脚本里声明了几个 {@code engine.input.<key>} 就有几行。
     *
     * <p>值存在 profile 里、随配置广播下发到 Gboard 进程，脚本通过 {@code ctx.config} 取。
     * 键名里带 secret/key/token/password 的当密码框（带显示切换）。
     */
    private void showInputDialog(VoiceProfiles.Profile p) {
        final java.util.List<String> keys = VoiceProfiles.inputKeys(p.script);
        final java.util.Map<String, org.json.JSONObject> meta = VoiceProfiles.formMeta(this, p.id);
        android.util.Log.i("GboardExt", "voice ui: click " + p.id + " keys=" + keys);
        // 没有 engine.input.* 也要弹：用户脚本还得在这里**显式确认「可访问域名」**（空 = 禁止联网）
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        final int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final java.util.Map<String, android.widget.EditText> fields = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            final org.json.JSONObject m = meta.get(k);
            // 标签/密文优先用 index.json 的声明（内置项有），没有就退回"键名 + 按名字猜"
            final String label = m != null && !m.optString("label", "").isEmpty()
                    ? m.optString("label") : k;
            final boolean secret = m != null && m.has("secret") ? m.optBoolean("secret", false)
                    : k.matches("(?i).*(secret|key|token|password|passwd).*");
            final com.google.android.material.textfield.TextInputLayout til =
                    new com.google.android.material.textfield.TextInputLayout(this);
            til.setHint(label);
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setText(p.config.containsKey(k) ? p.config.get(k)
                    : VoiceProfiles.inputDefault(p.script, k));
            et.setSingleLine(true);
            if (secret) {
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                til.setEndIconMode(
                        com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            }
            til.addView(et);
            final android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = pad / 2;
            til.setLayoutParams(lp);
            box.addView(til);
            fields.put(k, et);
        }

        // 白名单：宿主会**强制**校验（见 ScriptEngine.checkHost）。空 = 一个域名都不许连，
        // 所以这里必须让用户看得见、改得动 —— 用户口径："用户脚本默认给空名单，要显式确认"。
        final com.google.android.material.textfield.TextInputLayout hostTil =
                new com.google.android.material.textfield.TextInputLayout(this);
        hostTil.setHint(p.builtin ? "可访问域名（留空 = 禁止联网）" : "可访问域名（必填，留空 = 禁止联网）");
        final android.widget.EditText hostEt = new android.widget.EditText(this);
        hostEt.setText(String.join(" ", p.hosts));
        hostEt.setSingleLine(true);
        hostTil.addView(hostEt);
        hostTil.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(hostTil);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(p.label + "（" + p.id + "）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    final java.util.Map<String, String> vals = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, android.widget.EditText> e : fields.entrySet()) {
                        vals.put(e.getKey(), e.getValue().getText().toString().trim());
                    }
                    VoiceProfiles.setConfig(this, p.id, vals);
                    VoiceProfiles.setHosts(this, p.id,
                            VoiceProfiles.normalizeHosts(hostEt.getText().toString()));
                    ConfigSender.sendAndRetry(this);
                    render();
                    Toast.makeText(this, "已保存（下次语音生效）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** BZK 同款按压反馈：整棵子树按到 0.98，松手弹回（监听器一律返回 false，不吞事件）。 */
    private void attachPressFeedback(android.view.View root) {
        final android.view.View.OnTouchListener l = (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    root.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    root.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        };
        walk(root, v -> v.setOnTouchListener(l));
    }

    /** 长按触发时的短脉冲（BZK 同款）。 */
    private void pulseLongPress(android.view.View v) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start())
                .start();
    }

    private void walk(android.view.View v, java.util.function.Consumer<android.view.View> a) {
        a.accept(v);
        if (v instanceof android.view.ViewGroup) {
            final android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), a);
        }
    }

    private void copy(String text) {
        try {
            final ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("voice-engine", text));
        } catch (Throwable ignored) {
        }
    }

    private int themeColor(int attrRes) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(this, tv.resourceId);
        }
        return tv.data;
    }
}
