package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 符号宽度归一：中文态下把"本来就是 ASCII 的符号"从全角拉回半角。
 *
 * <p><b>只管宽窄，不碰语义</b>：{@code ，。！？；：、（）「」『』“”} 是中文标点本身，
 * 不归这里管（要动它们属于"中文标点 / 英文标点"那套语义层，本轮不做）。
 *
 * <p>表可配置：一串「全角→半角」相邻成对，空白与换行只当分组（与隔壁 Sogou 的配对表
 * 同一套写法）；留空 = 不处理。
 *
 * <p>为什么在 commitText 环节做：Gboard 的中文标点映射在它内部，等到提交时字符已经
 * 定型了，只能在这里"再归一"。
 */
final class SymbolNorm {

    private static final String TAG = "GboardExt";

    /**
     * 默认表：中文态下 Gboard 会打成全角、但语义上就是 ASCII 的 13 个符号。
     *
     * <p>刻意<b>不含</b> {@code + =}（用户实测那两个本来就正常）、
     * 也不含任何中文标点。
     */
    static final String DEFAULT_TABLE =
            "｛{｝}／/｜|\n"
            + "＠@＃#％%＆&\n"
            + "＊*｀`～~\n"
            + "＿_－-";

    /** 并行数组：sFrom[i] 换成 sTo[i]。表很小，线性找足够。 */
    private static volatile char[] sFrom = new char[0];
    private static volatile char[] sTo = new char[0];

    private SymbolNorm() {}

    /** 解析并装载映射表（奇数个字符时最后一个丢掉，UI 侧也会先校验）。 */
    static void setTable(String raw) {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        if (raw != null) {
            final String s = raw.replaceAll("\\s+", "");
            for (int i = 0; i + 1 < s.length(); i += 2) {
                a.append(s.charAt(i));
                b.append(s.charAt(i + 1));
            }
        }
        sFrom = a.toString().toCharArray();
        sTo = b.toString().toCharArray();
        Log.i(TAG, "norm table set: " + sFrom.length + " pair(s)");
    }

    static int size() {
        return sFrom.length;
    }

    /**
     * 串里有没有"可能是全角符号"的字符（FF01–FF5E / 全角空格）。
     *
     * <p>只用于开发期诊断：把"路过但没被改写"的提交也打出来，
     * 这样能区分"没走到我们的挂点"和"走了但没命中表"。
     */
    static boolean hasFullWidth(CharSequence src) {
        if (src == null) return false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if ((c >= 0xFF01 && c <= 0xFF5E) || c == 0x3000) return true;
        }
        return false;
    }

    /** 命中就返回新串；没命中返回 {@code null}（调用方原样放行，零额外分配）。 */
    static String apply(CharSequence src) {
        final char[] from = sFrom;
        if (src == null || src.length() == 0 || from.length == 0) return null;
        final char[] to = sTo;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            char out = c;
            for (int k = 0; k < from.length; k++) {
                if (from[k] == c) {
                    out = to[k];
                    break;
                }
            }
            if (out != c) changed = true;
            sb.append(out);
        }
        return changed ? sb.toString() : null;
    }
}
