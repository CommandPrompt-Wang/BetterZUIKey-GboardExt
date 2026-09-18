package moe.lovefirefly.bzk.gboardext;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

/**
 * GboardExt 的配置：严格模式 + 「完整的 …… 和 ——」。
 *
 * <p>通道是**显式广播**（见 {@link BroadcastConfig}）—— provider 那条在 Gboard 上因包可见性
 * 走不通（ANALYSIS.md §15）。符号归一的表是硬编码的（{@link SymbolNorm}），不走配置。
 */
final class GboardConfig {

    static final String TAG = "GboardExt";
    static final String PREFS_NAME = "gboardext_config";
    static final String KEY_STRICT = "strict";
    static final String KEY_LONG = "longMarks";
    static final String KEY_NUMBER = "smartNumbering";
    static final String KEY_ENTER = "enterCommitPinyin";
    static final String KEY_SMART_PUNCT = "smartPunct";
    static final String KEY_FULLWIDTH = "fullwidth";
    static final String KEY_EN_PUNCT = "enPunct";

    /** 严格模式：语言只由框架/BZK 决定（拦掉 Gboard 自己切布局/语言）。 */
    final boolean strict;

    /** 「完整的 …… 和 ——」：关 = 一个（搜狗原生），开 = 两个（中文排版标准）。 */
    final boolean longMarks;

    /** 智能编号：数字后面的 。/） 自动半角（1. 2) 这种）。 */
    final boolean smartNumbering;

    /**
     * 中文态 Enter 不误提交：拼音栏有字时按 Enter 只把原始拼音上屏、
     * 不让那颗回车落到 App 手里（否则搜索框/消息栏会被直接提交）。默认<b>关</b>（用户定）。
     */
    final boolean enterCommitPinyin;

    /** 智能中文标点：·/—/… 那套语义层。默认开。 */
    final boolean smartPunct;

    /** 全角模式：启用"全角/半角"这个状态位（Shift+Space 切）。默认开，状态默认半角。 */
    final boolean fullwidth;

    /** 中英文标点：启用"中文标点/英文标点"这个状态位（Ctrl+. 切）。默认开，状态默认中文标点。 */
    final boolean enPunct;

    private GboardConfig(boolean strict, boolean longMarks, boolean smartNumbering,
            boolean enterCommitPinyin, boolean smartPunct, boolean fullwidth, boolean enPunct) {
        this.strict = strict;
        this.longMarks = longMarks;
        this.smartNumbering = smartNumbering;
        this.enterCommitPinyin = enterCommitPinyin;
        this.smartPunct = smartPunct;
        this.fullwidth = fullwidth;
        this.enPunct = enPunct;
    }

    static GboardConfig defaults() {
        return new GboardConfig(true, true, true, false, true, true, true);
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, true),
                sp.getBoolean(KEY_LONG, true), sp.getBoolean(KEY_NUMBER, true),
                sp.getBoolean(KEY_ENTER, false),
                sp.getBoolean(KEY_SMART_PUNCT, true),
                sp.getBoolean(KEY_FULLWIDTH, true),
                sp.getBoolean(KEY_EN_PUNCT, true));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, true)
                + "&" + KEY_LONG + "=" + sp.getBoolean(KEY_LONG, true)
                + "&" + KEY_NUMBER + "=" + sp.getBoolean(KEY_NUMBER, true)
                + "&" + KEY_ENTER + "=" + sp.getBoolean(KEY_ENTER, false)
                + "&" + KEY_SMART_PUNCT + "=" + sp.getBoolean(KEY_SMART_PUNCT, true)
                + "&" + KEY_FULLWIDTH + "=" + sp.getBoolean(KEY_FULLWIDTH, true)
                + "&" + KEY_EN_PUNCT + "=" + sp.getBoolean(KEY_EN_PUNCT, true);
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = true;
        boolean longMarks = true;
        boolean smartNumbering = true;
        boolean enterCommitPinyin = false;
        boolean smartPunct = true;
        boolean fullwidth = true;
        boolean enPunct = true;
        for (String kv : raw.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            final String k = kv.substring(0, i);
            final String v = kv.substring(i + 1);
            if (KEY_STRICT.equals(k)) strict = Boolean.parseBoolean(v);
            else if (KEY_LONG.equals(k)) longMarks = Boolean.parseBoolean(v);
            else if (KEY_NUMBER.equals(k)) smartNumbering = Boolean.parseBoolean(v);
            else if (KEY_ENTER.equals(k)) enterCommitPinyin = Boolean.parseBoolean(v);
            else if (KEY_SMART_PUNCT.equals(k)) smartPunct = Boolean.parseBoolean(v);
            else if (KEY_FULLWIDTH.equals(k)) fullwidth = Boolean.parseBoolean(v);
            else if (KEY_EN_PUNCT.equals(k)) enPunct = Boolean.parseBoolean(v);
        }
        return new GboardConfig(strict, longMarks, smartNumbering, enterCommitPinyin,
                smartPunct, fullwidth, enPunct);
    }

    String signature() {
        return "strict=" + strict + "|long=" + longMarks + "|num=" + smartNumbering
                + "|enter=" + enterCommitPinyin
                + "|sp=" + smartPunct + "|fw=" + fullwidth + "|ep=" + enPunct;
    }

    /** 模块侧读配置：优先 App 的 ContentProvider（这条在 Gboard 上走不通，留作兜底）。 */
    static String readProvider(ContentResolver cr) {
        if (cr == null) return null;
        try (Cursor c = cr.query(ConfigProvider.URI, null, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
            Log.d(TAG, "provider: empty cursor");
        } catch (Throwable err) {
            Log.d(TAG, "provider read failed: " + err);
        }
        return null;
    }
}
