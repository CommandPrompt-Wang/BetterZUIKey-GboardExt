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
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_engine);
        inflater = LayoutInflater.from(this);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        listProfiles = findViewById(R.id.list_profiles);
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
        render();          // 从「添加配置文件」回来要立刻看到新条目
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        listProfiles.removeAllViews();
        final List<VoiceProfiles.Profile> list = VoiceProfiles.load(this);
        for (VoiceProfiles.Profile p : list) {
            final View row = inflater.inflate(R.layout.item_voice_row, listProfiles, false);
            final MaterialCardView card = (MaterialCardView) row;
            final CheckBox cb = row.findViewById(R.id.cb_enabled);
            final TextView name = row.findViewById(R.id.tv_name);
            final TextView idTv = row.findViewById(R.id.tv_id);

            name.setText(p.label == null || p.label.isEmpty() ? p.id : p.label);
            idTv.setText(p.id);
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
        android.util.Log.i("GboardExt", "voice ui: click " + p.id + " keys=" + keys);
        if (keys.isEmpty()) {
            Toast.makeText(this, p.id + " 没有可填写的项（脚本里没有 engine.input.*）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        final int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final java.util.Map<String, android.widget.EditText> fields = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            final com.google.android.material.textfield.TextInputLayout til =
                    new com.google.android.material.textfield.TextInputLayout(this);
            til.setHint(k);
            final boolean secret = k.matches("(?i).*(secret|key|token|password|passwd).*");
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

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(p.label + "（" + p.id + "）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    final java.util.Map<String, String> vals = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, android.widget.EditText> e : fields.entrySet()) {
                        vals.put(e.getKey(), e.getValue().getText().toString().trim());
                    }
                    VoiceProfiles.setConfig(this, p.id, vals);
                    ConfigSender.sendAndRetry(this);
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
