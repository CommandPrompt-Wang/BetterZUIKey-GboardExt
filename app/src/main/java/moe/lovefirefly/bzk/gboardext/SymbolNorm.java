package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 中文态下把 Gboard "粗暴全角化"出来的符号改对。
 *
 * <p><b>两类处理，性质完全不同</b>：
 * <ol>
 *   <li><b>宽度层</b>：本来就是 ASCII、却被 Gboard 打成全角的
 *       （{@code ｛｝／｜＠＃％＆＊～－}）→ 拉回半角；</li>
 *   <li><b>语义层</b>：这个键在中文里<b>本来就该出别的字符</b> ——
 *       反引号键该出姓名圆点 {@code ·}、下划线键该出破折号 {@code —}、省略号同理。
 *       这些不是"宽窄"问题，是"该出什么"的问题。</li>
 * </ol>
 *
 * <p>为什么要分这两层：隔壁 SogouOEMExt 里那条 {@code · → ASCII 反引号} 是
 * <b>我们自己加的覆盖</b>（搜狗原生就出 {@code ·}，本来就是对的）；而 Gboard 是
 * <b>粗暴全角化</b>，所以这里才需要修它。两者的方向正好相反。
 *
 * <p><b>表硬编码</b>：键盘就那么大，能出到的符号是固定那几个（用户定）。
 * 加字符改 {@link #WIDTH}；改语义特例看 {@link #apply}。
 *
 * <p>为什么在 commitText 环节做：Gboard 的映射在它内部，等提交时字符已经定型，
 * 只能在这里"再归一"。
 */
final class SymbolNorm {

    private static final String TAG = "GboardExt";

    /**
     * 宽度层：全角 → 半角，相邻两个字符一对；换行只当分组，纯为可读。
     *
     * <p>刻意<b>不含</b> {@code ＋＝}（实测那两个本来就正常）、不含任何中文标点，
     * 也不含反引号与下划线（那两个走语义层，见 {@link #apply}）。
     */
    private static final String WIDTH =
            "｛{｝}／/｜|\n"
            + "＠@＃#％%＆&\n"
            + "＊*～~\n"
            + "－-";

    /** 姓名圆点（中文人名分隔，如 克里斯·埃文斯）。 */
    private static final char NAME_DOT = '\u00B7';

    /** 破折号 / 省略号的单字（中文排版标准是各两个，见 {@link #sLongMarks}）。 */
    private static final char DASH = '\u2014';
    private static final char ELLIPSIS = '\u2026';

    /**
     * 「完整的 …… 和 ——」：关闭 = 一个（搜狗原生就是这样），开启 = 两个（中文排版标准）。
     *
     * <p>默认开。App 里那个开关通过广播把值推过来。
     */
    private static volatile boolean sLongMarks = true;

    /** 并行数组：FROM[i] 换成 TO[i]。表很小，线性找足够。 */
    private static final char[] FROM;
    private static final char[] TO;

    static {
        final String flat = WIDTH.replaceAll("\\s+", "");
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < flat.length(); i += 2) {
            a.append(flat.charAt(i));
            b.append(flat.charAt(i + 1));
        }
        FROM = a.toString().toCharArray();
        TO = b.toString().toCharArray();
        Log.i(TAG, "norm: width " + FROM.length + " pair(s), longMarks=" + sLongMarks);
    }

    private SymbolNorm() {}

    static void setLongMarks(boolean on) {
        if (sLongMarks != on) Log.i(TAG, "norm: longMarks -> " + on);
        sLongMarks = on;
    }

    static int size() {
        return FROM.length;
    }

    /**
     * 归一：命中就返回新串，没命中返回 {@code null}（调用方原样放行，零额外分配）。
     *
     * <p>语义层优先（反引号 / 下划线 / 省略号），其余走宽度表。
     */
    static String apply(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final boolean longMarks = sLongMarks;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            // —— 语义层 ——
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
            if (c == ELLIPSIS) {                       // … 省略号 → 按开关补成完整的
                int n = 0;
                while (i + n < src.length() && src.charAt(i + n) == ELLIPSIS) n++;
                i += n - 1;
                sb.append(ELLIPSIS);
                if (longMarks) sb.append(ELLIPSIS);
                if (n != (longMarks ? 2 : 1)) changed = true;
                continue;
            }
            // —— 宽度层 ——
            char out = c;
            for (int k = 0; k < FROM.length; k++) {
                if (FROM[k] == c) {
                    out = TO[k];
                    break;
                }
            }
            if (out != c) changed = true;
            sb.append(out);
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 串里有没有"可能是全角符号"的字符（FF01–FF5E / 全角空格）。
     *
     * <p>只用于开发期诊断：把"路过但没被改写"的提交也打出来，用来区分
     * "没走到挂点"和"走了但没命中表"。
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
