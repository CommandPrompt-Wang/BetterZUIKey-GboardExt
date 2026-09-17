package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 符号宽度归一：中文态下把"本来就是 ASCII 的符号"从全角拉回半角。
 *
 * <p><b>表是硬编码的</b>：键盘上能出到的全角 ASCII 符号就那么固定几个（用户拍板：
 * "键盘就那么大"）—— 做成配置项只会多出一条配置通道、一个编辑界面和一堆状态，
 * 收益为零。要增删符号，改 {@link #TABLE} 一处即可。
 *
 * <p><b>只管宽窄，不碰语义</b>：{@code ，。！？；：、（）「」『』“”} 是中文标点本身，
 * 不在表里（要动它们属于"中文标点 / 英文标点"那套语义层，与本功能无关）。
 *
 * <p>为什么在 commitText 环节做：Gboard 的中文标点映射在它内部，等提交时字符已经定型，
 * 只能在这里"再归一"。
 */
final class SymbolNorm {

    private static final String TAG = "GboardExt";

    /**
     * 全角 → 半角，相邻两个字符一对；换行只当分组，纯粹为了好读。
     *
     * <p>就是这 13 个：{@code ｛｝／｜＠＃％＆＊｀～＿－}。
     * 刻意<b>不含</b> {@code ＋＝}（实测那两个本来就正常），也不含任何中文标点。
     */
    private static final String TABLE =
            "｛{｝}／/｜|\n"
            + "＠@＃#％%＆&\n"
            + "＊*｀`～~\n"
            + "＿_－-";

    /** 并行数组：FROM[i] 换成 TO[i]。表很小，线性找足够。 */
    private static final char[] FROM;
    private static final char[] TO;

    static {
        final String flat = TABLE.replaceAll("\\s+", "");
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < flat.length(); i += 2) {
            a.append(flat.charAt(i));
            b.append(flat.charAt(i + 1));
        }
        FROM = a.toString().toCharArray();
        TO = b.toString().toCharArray();
        Log.i(TAG, "norm table: " + FROM.length + " pair(s)");
    }

    private SymbolNorm() {}

    static int size() {
        return FROM.length;
    }

    /** 命中就返回新串；没命中返回 {@code null}（调用方原样放行，零额外分配）。 */
    static String apply(CharSequence src) {
        if (src == null || src.length() == 0 || FROM.length == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
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
