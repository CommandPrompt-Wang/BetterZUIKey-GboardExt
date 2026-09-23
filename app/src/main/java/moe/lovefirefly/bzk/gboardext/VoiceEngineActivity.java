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
            cb.setOnCheckedChangeListener((v, checked) -> {
                VoiceProfiles.setEnabled(VoiceEngineActivity.this, p.id, checked);
                ConfigSender.sendAndRetry(VoiceEngineActivity.this);
                render();          // 把被自动取消的那些勾选框刷新掉
            });
            // 点整张卡片等价于点勾选框（长按留给"复制代码"，与 BZK 的"长按进子页"同一位置）
            card.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            card.setOnLongClickListener(v -> {
                copy(p.script);
                Toast.makeText(this, "已复制 " + p.id + " 的代码", Toast.LENGTH_SHORT).show();
                return true;
            });
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
