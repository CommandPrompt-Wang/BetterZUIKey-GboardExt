package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 中文态符号归一：**语义层**（该出什么字符）+ **宽度层**（宽窄）。
 *
 * <p>拆成两层是为了让它们分别受"智能中文标点 / 中英文标点"和"全角模式"控制
 * （与搜狗模块的管线一致；语义层先决定是哪个字符，宽度层最后决定宽窄）。
 *
 * <h3>语义层</h3>
 * <ul>
 *   <li>反引号键 {@code ｀}(FF40) → 姓名圆点 {@code ·}(U+00B7)；</li>
 *   <li>下划线键 {@code ＿}(FF3F) → 破折号 {@code —}(U+2014)（{@code longMarks} 时两个）；</li>
 *   <li>省略号 {@code …} 的连续段 → 按 {@code longMarks} 归一成 1 / 2 个；</li>
 *   <li>中文标点 → ASCII（只在"中英文标点"切到英文时用）。</li>
 * </ul>
 *
 * <h3>宽度层为什么改成区间规则</h3>
 * 早先是"逐个补表"（15 对），结果漏了 {@code ＋＝}（用户实测报的 bug）。查 Gboard 的
 * {@code lib/arm64-v8a/libintegrated_shared_object.so} 后发现里面是一对紧挨着的字符串：
 * {@code !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~ } 与
 * {@code ！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～}
 * —— 一张覆盖**整个 ASCII 可打印区**的 1:1 全角表 ⇒ 补表永远会漏。
 * 所以半角化改成区间 {@code FF01–FF5E → ASCII}，只排除中文标点（它们本来就该是全角）。
 *
 * <p>软键盘的键面标签本身就是半角（全角形式在长按列表里），所以区间规则对软键盘天然是
 * no-op —— 不需要区分"提交来自软键盘还是物理键盘"。
 *
 * <p><b>全角化（{@code toFullWidth}）刻意只动"符号"、不动字母数字</b>：拼音串与英文候选
 * 也要走 commit/setComposing，若连字母一起全角化，中文态打字会变成全角拼音（很难看）。
 * 与搜狗那边"整段 ASCII 都全角化"略有差异，记录在此。
 */
final class SymbolNorm {

    private static final String TAG = "GboardExt";

    /** 中文标点：中文里本来就是全角，**不许**被半角化。 */
    private static final String KEEP_CN = "！？；：，（）";

    /**
     * 中文标点 → ASCII（"中英文标点"切到英文时用；一一对应）。
     *
     * <p>不收 {@code 、}：它的物理键在 Gboard 上分不出来（§22：提交层已丢物理键身份）。
     */
    private static final String CN_PUNCT = "，。！？；：（）【】《》〈〉“”‘’";
    private static final String CN_PUNCT_ASCII = ",.!?;:()[]<><>\"\"''";

    /** 姓名圆点（中文人名分隔，如 克里斯·埃文斯）。 */
    private static final char NAME_DOT = '\u00B7';

    /** 破折号 / 省略号的单字（中文排版标准是各两个，见 {@link #sLongMarks}）。 */
    private static final char DASH = '\u2014';
    private static final char ELLIPSIS = '\u2026';

    /**
     * 已知会被 Gboard 全角化的符号（文档 + 单测回归样本；实际转换走区间规则）。
     *
     * <p>早先注释写"刻意不含 ＋＝（实测本来就正常）"是**错的**，用户实测两键同样被全角化。
     */
    static final String KNOWN_FULLWIDTH =
            "｛{｝}／/｜|\n"
            + "＠@＃#％%＆&\n"
            + "＊*～~\n"
            + "－-＋+＝=\n"
            + "＄$＾^";

    /**
     * 「完整的 …… 和 ——」：关闭 = 一个（搜狗原生），开启 = 两个（中文排版标准）。默认开。
     */
    private static volatile boolean sLongMarks = true;

    /** 已知对儿解析出来的数量（启动自检日志用）。 */
    private static final int KNOWN_PAIRS;

    static {
        KNOWN_PAIRS = KNOWN_FULLWIDTH.replaceAll("\\s+", "").length() / 2;
        Log.i(TAG, "norm: known fullwidth pairs=" + KNOWN_PAIRS
                + ", longMarks=" + sLongMarks);
    }

    private SymbolNorm() {}

    static void setLongMarks(boolean on) {
        if (sLongMarks != on) Log.i(TAG, "norm: longMarks -> " + on);
        sLongMarks = on;
    }

    static boolean longMarks() {
        return sLongMarks;
    }

    static int size() {
        return KNOWN_PAIRS;
    }

    // ------------------------------------------------------------------ 语义层

    /**
     * 语义层归一：{@code ｀→·}、{@code ＿→—}（或 {@code ——}）、{@code …} 段按开关补成 1 / 2 个。
     *
     * <p>命中才返回新串，没命中返回 {@code null}（调用方原样放行，零额外分配）。
     */
    static String applySemantic(CharSequence src, boolean longMarks) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '\uFF40') {                       // ｀ 全角反引号键 → 姓名圆点
                sb.append(NAME_DOT);
                changed = true;
                continue;
            }
            if (c == '\uFF3F') {                       // ＿ 下划线键 → 破折号
                sb.append(DASH);
                if (longMarks) sb.append(DASH);
                changed = true;
                continue;
            }
            if (c == ELLIPSIS) {                       // … 的连续段 → 1 或 2 个（幂等）
                int n = 0;
                while (i + n < src.length() && src.charAt(i + n) == ELLIPSIS) n++;
                i += n - 1;
                sb.append(ELLIPSIS);
                if (longMarks) sb.append(ELLIPSIS);
                if (n != (longMarks ? 2 : 1)) changed = true;
                continue;
            }
            sb.append(c);
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 中文标点 → ASCII（"中英文标点"切到英文时那一侧）。命中才返回新串，否则 {@code null}。
     */
    static String toAsciiPunct(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            final int idx = CN_PUNCT.indexOf(c);
            if (idx < 0) {
                sb.append(c);
                continue;
            }
            sb.append(CN_PUNCT_ASCII.charAt(idx));
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    // ------------------------------------------------------------------ 宽度层

    /**
     * 半角化：全角 ASCII 区 {@code FF01–FF5E} → ASCII，排除中文标点 {@link #KEEP_CN}。
     *
     * <p>（{@code ＿}(FF3F) / {@code ｀}(FF40) 不在排除集里：语义层应先把它们变成 {@code —}/{@code ·}；
     * 语义层被关掉时，它们就按"宽窄"这一层老老实实落回 {@code _} / {@code `} —— 与搜狗的层次一致。）
     */
    static String toHalfWidth(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E && KEEP_CN.indexOf(c) < 0) {
                sb.append((char) (c - 0xFEE0));
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 全角化：ASCII **符号区** → {@code FF01–FF5E}。
     *
     * <p>只动符号（{@code 0x21–0x2F}、{@code 0x3A–0x40}、{@code 0x5B–0x60}、{@code 0x7B–0x7E}），
     * <b>不动字母、数字与空格</b> —— 理由见类注释（别把拼音/英文全角化）。
     */
    static String toFullWidth(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if ((c >= 0x21 && c <= 0x2F) || (c >= 0x3A && c <= 0x40)
                    || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E)) {
                sb.append((char) (c + 0xFEE0));
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 串里有没有"可能是全角符号"的字符（FF01–FF5E / 全角空格）。
     *
     * <p>只用于开发期诊断：把"路过但没被改写"的提交也打出来，用来区分
     * "没走到挂点"和"走了但没命中"。
     */
    static boolean hasFullWidth(CharSequence src) {
        if (src == null) return false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if ((c >= 0xFF01 && c <= 0xFF5E) || c == 0x3000) return true;
        }
        return false;
    }
}
