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
    static final String KEY_AUTO_PAIR = "autoPair";
    static final String KEY_PHYS_COMPLETE = "physComplete";
    static final String KEY_PAIR_TABLE = "autoPairTable";

    /**
     * 语音引擎 id（见 local/VOICE-ENGINE-INTERFACE.md）。
     * 空串 = <b>不接管</b>，Gboard 走它自己的 S3（零配置零回归）；内置 id 见 assets/engines/index.json。
     */
    static final String KEY_ENGINE = "voiceEngine";

    /** 「替换语音输入」总开关（设置页那张卡片）。开=接管；关=透传。 */
    static final String KEY_VOICE_ENABLED = "voiceEnabled";

    /**
     * 「长按应急切换状态位」的**期望值**通道（App → 模块，一次性）。
     *
     * <p>为什么不让 App 直接写状态位：状态位在 **Gboard 进程**的 prefs 里，App 物理上写不到。
     * 所以 App 只写"我希望它变成什么 + 一个序号"，模块收到后应用一次、再把结果镜像回来
     * （与搜狗组件同一套做法）。
     */
    static final String KEY_WANT_FULL = "wantFullwidth";
    static final String KEY_WANT_ENP = "wantEnPunct";
    static final String KEY_WANT_PHYS = "wantPhysComplete";
    /** 序号用时间戳：不存在溢出；App 清数据后新值必然更大 ⇒ 不会永久失效。 */
    static final String KEY_WANT_SEQ = "wantSeq";

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

    /** 引号/括号自动补全（软键盘那一侧，模块自己注入闭字符）。默认<b>开</b>。 */
    final boolean autoPair;

    /** 物理键盘自动补全（模块自己注入闭字符 + 光标左移）。默认关（状态位默认开）。 */
    final boolean physComplete;

    /** 配对表（相邻两字符一组；空 = 不配对）。 */
    final String autoPairTable;

    private GboardConfig(boolean strict, boolean longMarks, boolean smartNumbering,
            boolean enterCommitPinyin, boolean smartPunct, boolean fullwidth, boolean enPunct,
            boolean autoPair, boolean physComplete, String autoPairTable) {
        this.strict = strict;
        this.longMarks = longMarks;
        this.smartNumbering = smartNumbering;
        this.enterCommitPinyin = enterCommitPinyin;
        this.smartPunct = smartPunct;
        this.fullwidth = fullwidth;
        this.enPunct = enPunct;
        this.autoPair = autoPair;
        this.physComplete = physComplete;
        this.autoPairTable = autoPairTable == null ? GboardPair.DEFAULT_TABLE : autoPairTable;
    }

    /** 编译期默认（与 App 侧建页的默认一致）：严格 / 物理补全 / 中文态 Enter = 关，其余开。 */
    static GboardConfig defaults() {
        return new GboardConfig(false, true, true, false, true, true, true,
                true, false, GboardPair.DEFAULT_TABLE);
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, false),
                sp.getBoolean(KEY_LONG, true), sp.getBoolean(KEY_NUMBER, true),
                sp.getBoolean(KEY_ENTER, false),
                sp.getBoolean(KEY_SMART_PUNCT, true),
                sp.getBoolean(KEY_FULLWIDTH, true),
                sp.getBoolean(KEY_EN_PUNCT, true),
                sp.getBoolean(KEY_AUTO_PAIR, true),
                sp.getBoolean(KEY_PHYS_COMPLETE, false),
                sp.getString(KEY_PAIR_TABLE, GboardPair.DEFAULT_TABLE));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, false)
                + "&" + KEY_LONG + "=" + sp.getBoolean(KEY_LONG, true)
                + "&" + KEY_NUMBER + "=" + sp.getBoolean(KEY_NUMBER, true)
                + "&" + KEY_ENTER + "=" + sp.getBoolean(KEY_ENTER, false)
                + "&" + KEY_SMART_PUNCT + "=" + sp.getBoolean(KEY_SMART_PUNCT, true)
                + "&" + KEY_FULLWIDTH + "=" + sp.getBoolean(KEY_FULLWIDTH, true)
                + "&" + KEY_EN_PUNCT + "=" + sp.getBoolean(KEY_EN_PUNCT, true)
                + "&" + KEY_AUTO_PAIR + "=" + sp.getBoolean(KEY_AUTO_PAIR, true)
                + "&" + KEY_PHYS_COMPLETE + "=" + sp.getBoolean(KEY_PHYS_COMPLETE, false)
                + "&" + KEY_PAIR_TABLE + "=" + GboardPair.encode(
                        sp.getString(KEY_PAIR_TABLE, GboardPair.DEFAULT_TABLE));
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = false;
        boolean longMarks = true;
        boolean smartNumbering = true;
        boolean enterCommitPinyin = false;
        boolean smartPunct = true;
        boolean fullwidth = true;
        boolean enPunct = true;
        boolean autoPair = true;
        boolean physComplete = false;
        String pairTable = GboardPair.DEFAULT_TABLE;
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
            else if (KEY_AUTO_PAIR.equals(k)) autoPair = Boolean.parseBoolean(v);
            else if (KEY_PHYS_COMPLETE.equals(k)) physComplete = Boolean.parseBoolean(v);
            else if (KEY_PAIR_TABLE.equals(k)) pairTable = GboardPair.decode(v);
        }
        return new GboardConfig(strict, longMarks, smartNumbering, enterCommitPinyin,
                smartPunct, fullwidth, enPunct, autoPair, physComplete, pairTable);
    }

    String signature() {
        return "strict=" + strict + "|long=" + longMarks + "|num=" + smartNumbering
                + "|enter=" + enterCommitPinyin
                + "|sp=" + smartPunct + "|fw=" + fullwidth + "|ep=" + enPunct
                + "|pair=" + autoPair + "|phys=" + physComplete
                + "|pairtbl=" + GboardPair.clean(autoPairTable);
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
