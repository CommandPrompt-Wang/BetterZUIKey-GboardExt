package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

/**
 * 顿号映射：中文态下决定 {@code 、}（顿号）从哪个键出。
 *
 * <p><b>与搜狗不同</b>：搜狗把 {@code /} 和 {@code \} 都出成 {@code 、}，所以它那边要按
 * "上一个物理键"去区分；<b>Gboard 自己就分开了</b> —— {@code \} 键出 {@code 、}，
 * {@code /} 键出 {@code /}（原生默认）。
 *
 * <p>因此这里只要按<b>字符</b>判断，不需要知道按的是哪个键：
 *
 * <table>
 *   <tr><th>模式</th><th>按 {@code \}（Gboard 给 {@code 、}）</th><th>按 {@code /}（Gboard 给 {@code /}）</th></tr>
 *   <tr><td>{@link #MODE_BACKSLASH}（默认）</td><td>{@code 、}</td><td>{@code /}</td></tr>
 *   <tr><td>{@link #MODE_SLASH}</td><td>{@code \}</td><td>{@code 、}</td></tr>
 *   <tr><td>{@link #MODE_ALL}</td><td>{@code 、}</td><td>{@code 、}</td></tr>
 * </table>
 *
 * <p><b>必须在 {@link SymbolNorm} 之前跑</b>：否则全角 {@code ／} 会先被宽度层改成
 * ASCII {@code /}，再被这里当成"顿号键"误伤。
 *
 * <p>已知取舍：模式 {@link #MODE_SLASH} 是"字符级"判断，所以从符号页/候选里点出来的
 * {@code 、} 也会被还原成 {@code \}。要区分就得知道"是哪个物理键按的"，
 * 那要 hook Gboard 自己的按键处理（DexKit 结构化定位那条路）。
 */
final class SlashMap {

    private static final String TAG = "GboardExt";

    /** 默认：Gboard 原生行为（{@code \} → 、，{@code /} → /）。 */
    static final int MODE_BACKSLASH = 0;
    /** 顿号改由 {@code /} 键出，{@code \} 键还原成反斜杠。 */
    static final int MODE_SLASH = 1;
    /** 两个键都出顿号。 */
    static final int MODE_ALL = 2;

    private static volatile int sMode = MODE_BACKSLASH;

    private SlashMap() {}

    static int mode() {
        return sMode;
    }

    static void setMode(int mode) {
        final int v = (mode < 0 || mode > MODE_ALL) ? MODE_BACKSLASH : mode;
        if (sMode != v) Log.i(TAG, "slash: 顿号映射 -> " + name(v));
        sMode = v;
    }

    static String name(int mode) {
        switch (mode) {
            case MODE_SLASH: return "/";
            case MODE_ALL: return "全部";
            default: return "\\";
        }
    }

    /** 命中就返回新串，没命中返回 {@code null}。 */
    static String apply(CharSequence src) {
        final int mode = sMode;
        if (src == null || src.length() == 0 || mode == MODE_BACKSLASH) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '/') {
                // 模式 / 与 全部：斜杠键改出顿号
                sb.append('\u3001');
                changed = true;
                continue;
            }
            if (c == '\u3001' && mode == MODE_SLASH) {
                // 模式 /：Gboard 给反斜杠键的顿号要还原成反斜杠
                sb.append('\\');
                changed = true;
                continue;
            }
            sb.append(c);
        }
        return changed ? sb.toString() : null;
    }
}
