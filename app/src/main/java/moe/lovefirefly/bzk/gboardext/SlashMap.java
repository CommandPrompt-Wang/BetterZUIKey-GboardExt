package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 顿号映射：中文态下决定 {@code 、}（顿号）从哪个键出。
 *
 * <p><b>与搜狗不同</b>：搜狗把 {@code /} 和 {@code \} 都出成 {@code 、}，要靠"上一个物理键"区分；
 * <b>Gboard 自己就分开了</b> —— {@code \} 键出 {@code 、}，{@code /} 键出斜杠（原生默认）。
 *
 * <table>
 *   <tr><th>模式</th><th>按 {@code \}</th><th>按 {@code /}</th></tr>
 *   <tr><td>{@link #MODE_BACKSLASH}（默认）</td><td>{@code 、}</td><td>{@code /}</td></tr>
 *   <tr><td>{@link #MODE_ALL}</td><td>{@code 、}</td><td>{@code 、}</td></tr>
 * </table>
 *
 * <p><b>必须在 {@link SymbolNorm} 之前跑</b>：否则全角 {@code ／} 会先被宽度层改成 ASCII {@code /}，
 * 再被这里当成顿号键误伤。
 *
 * <p><b>为什么只有两档</b>（曾经有第三档"顿号改由 {@code /} 键出、{@code \} 键还原成反斜杠"）：
 * 那一档要求把"按了 {@code \} 键的顿号"和"符号页/候选点出来的顿号"分开 —— 二者提交的字符
 * 完全一样。实测（ANALYSIS.md §22）：DexKit 结构定位到提交漏斗 {@code Lnei.f} 并 hook 之后，
 * 三条路径在 <b>每一层都完全相同</b>（同样的 {@code commitText} 参数、同样的漏斗参数、同样的栈），
 * 提交 lambda 又是 R8 合并出来的（按字段选 lambda 体，三条路径用的是同一个体）。
 * ⇒ <b>Gboard 在提交之前就把物理键身份丢掉了</b>，该档无法精确实现，故删除。
 */
final class SlashMap {

    private static final String TAG = "GboardExt";

    /** 默认：Gboard 原生行为（{@code \} → 、，{@code /} → 斜杠）。 */
    static final int MODE_BACKSLASH = 0;
    /** 两个键都出顿号（唯一能精确实现的映射档）。 */
    static final int MODE_ALL = 1;

    private static volatile int sMode = MODE_BACKSLASH;

    private SlashMap() {}

    static int mode() {
        return sMode;
    }

    /** 只认这两档；旧版本存过的第三档（2）会落到 {@link #MODE_ALL}（语义最接近）。 */
    static void setMode(int mode) {
        final int v = (mode == MODE_ALL || mode >= 2) ? MODE_ALL : MODE_BACKSLASH;
        if (sMode != v) Log.i(TAG, "slash: 顿号映射 -> " + name(v));
        sMode = v;
    }

    static String name(int mode) {
        return mode == MODE_ALL ? "全部" : "\\";
    }

    /** 命中就返回新串，没命中返回 {@code null}。 */
    static String apply(CharSequence src) {
        if (src == null || src.length() == 0 || sMode != MODE_ALL) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '/' || c == '\uFF0F') {      // 半角 / 与全角 ／ 都算"斜杠键"
                sb.append('\u3001');
                changed = true;
                continue;
            }
            sb.append(c);
        }
        return changed ? sb.toString() : null;
    }
}
