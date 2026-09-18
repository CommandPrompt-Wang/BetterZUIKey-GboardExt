package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.inputmethod.InputConnection;

import java.util.Map;

/**
 * 引号/括号自动补全（模块自己实现，Gboard 原生没有这个行为）。
 *
 * <p>两条路，共用同一份配对表与同一段注入代码：
 * <ul>
 *   <li>{@code autoPair}（软键盘）：Gboard 的键面标签打出来的开字符上屏后补闭字符；</li>
 *   <li>{@code physComplete}（物理键盘）：同上，但只认"硬件按键期间"的提交
 *       （软键盘不走 {@code onKeyDown}，所以这个判据天然分得开，与搜狗模块同一思路）。</li>
 * </ul>
 *
 * <p>注入配方照搬搜狗那边验证过的写法：{@code beginBatchEdit → commitText(close,1) →
 * endBatchEdit → 光标左移}；左移只在能拿到"光标前完整文本"时做
 * （{@code getTextBeforeCursor} 触顶说明拿到的不是全量 ⇒ **宁可不动**，也不用假偏移把光标跳错地方）。
 *
 * <p>同字符对（{@code ""} / {@code ''}）靠模块**自己的翻转状态**决定这次是"开"还是"闭"
 * （搜狗那套 {@code KG} 翻转位是搜狗特有，gb 没有）。
 */
final class AutoPair {

    private static final String TAG = "GboardExt";

    private static volatile boolean sEnabled;          // autoPair（软键盘）
    private static volatile boolean sPhysEnabled;      // physComplete（物理键盘）
    private static volatile Map<Character, Character> sMap = GboardPair.parse(
            GboardPair.DEFAULT_TABLE);

    /** 正在注入 ⇒ 提交管线见到就原样放行（别把我们自己补的闭字符再改写一遍）。 */
    private static final ThreadLocal<Boolean> sInjecting =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 当前这次提交是否来自物理按键（由 {@link KeyRouter} 维护）。 */
    private static volatile boolean sHwKey;

    /** 同字符对的翻转状态：这个字符上一次是"开"还是"闭"。 */
    private static volatile char sLastToggleChar;
    private static volatile boolean sLastWasOpen;

    /**
     * 类似搜狗的 {@code q}：上一次**刚补出的那个闭字符**（{@code 0} = 无）。
     *
     * <p>存"是哪个字符"而不是"记个偏移"：偏移会被 {@link #moveCursorLeftOne}、输入法自己、
     * 以及**用户点击**改掉，记了转眼就过期（搜狗那边栽过）。判据只比字符，不做任何推导。
     */
    private static volatile char sJustPaired;

    /** 我们自己最后一次把光标放到的位置；用来区分"这次选区变化是用户点击造成的"（-1 = 未知）。 */
    private static volatile int sOwnCaret = -1;

    /** 诊断（默认关）。 */
    static final boolean DEV_TRACE = false;

    private AutoPair() {}

    static void setEnabled(boolean on) {
        if (sEnabled != on) Log.i(TAG, "autoPair -> " + on);
        sEnabled = on;
    }

    static void setPhysEnabled(boolean on) {
        if (sPhysEnabled != on) Log.i(TAG, "physComplete -> " + on);
        sPhysEnabled = on;
    }

    static void setTable(String raw) {
        if (raw == null || raw.isEmpty()) {           // 空表 = 不配对，没人想要；宁可保留现状
            Log.i(TAG, "pair table: empty ignored (keep " + sMap.size() + " opener(s))");
            return;
        }
        sMap = GboardPair.parse(raw);
        Log.i(TAG, "pair table: " + GboardPair.selfCheck(raw) + ", " + sMap.size() + " opener(s)");
    }

    static boolean isInjecting() {
        return Boolean.TRUE.equals(sInjecting.get());
    }

    /** 由 KeyRouter 在按键钩子里调用：标记"正在处理硬件按键"。 */
    static void setHardwareKey(boolean hw) {
        sHwKey = hw;
    }

    /**
     * 开字符上屏后补闭字符（在提交管线里、原提交完成之后调用）。
     *
     * <p>只认：中文态（调用方已判）、单字符、配对表里的**开**字符、且这一次的来源开关是开的。
     */
    static void maybeInject(final Object connection, final CharSequence committed) {
        if (connection == null || committed == null || committed.length() != 1) return;
        if (!(connection instanceof InputConnection)) return;
        final boolean hw = sHwKey;
        // 物理键盘这条路要**同时**看功能开关和 Ctrl+Shift+9 的状态位
        // （原来只看 sPhysEnabled ⇒ 那个热键除了横幅什么都不影响，等于空开关 ✗）
        if (hw ? !(sPhysEnabled && GboardState.physComplete()) : !sEnabled) {  // 按来源挑开关
            if (DEV_TRACE) Log.i(TAG, "pair skip: hw=" + hw + " enabled=" + sEnabled
                    + " phys=" + sPhysEnabled + " ch=" + committed);
            return;
        }
        final char open = committed.charAt(0);
        final Character close = sMap.get(open);           // 闭字符不是 key ⇒ 方向性天然成立
        if (close == null) {
            if (DEV_TRACE) Log.i(TAG, "pair not-opener: " + open + " hw=" + hw);
            return;
        }
        // 同字符对：这次是开还是闭由我们自己的翻转状态决定
        if (close == open && consumesAsClose(open)) return;
        final InputConnection ic = (InputConnection) connection;
        try {
            sInjecting.set(Boolean.TRUE);
            ic.beginBatchEdit();
            ic.commitText(String.valueOf(close), 1);
            ic.endBatchEdit();
            moveCursorLeftOne(ic);
            if (close == open) {
                sLastToggleChar = open;
                sLastWasOpen = true;                      // 这次是"开"，下回同一个字符当"闭"
            } else {
                markPaired(close);                        // 非引号：记下"刚补出的是哪个闭字符"
            }
            if (DEV_TRACE) Log.i(TAG, "pair: " + open + " -> " + open + close
                    + (hw ? " [hw]" : " [soft]"));
        } catch (Throwable tr) {
            Log.w(TAG, "pair inject failed: " + tr);
        } finally {
            sInjecting.set(Boolean.FALSE);
        }
    }

    /**
     * **有选区时把选区包起来**：选中 `abc` 打 `（` ⇒ `（abc）`（光标落在闭字符之后）。
     *
     * <p>必须在**原提交之前**调用（`getSelectedText` 只有那一刻还问得到 ——
     * 一旦 Gboard 把开字符提交上去，选区就被顶掉了 ✗）。
     *
     * @return true = 已经处理（调用方**不要**再走原提交）；false = 没选区/开关关着/不是开字符 ⇒ 走老路
     */
    static boolean maybeWrapSelection(final Object connection, final CharSequence committed) {
        if (connection == null || committed == null || committed.length() != 1) return false;
        if (!(connection instanceof InputConnection)) return false;
        final boolean hw = sHwKey;
        if (hw ? !(sPhysEnabled && GboardState.physComplete()) : !sEnabled) return false;
        final char open = committed.charAt(0);
        final Character close = sMap.get(open);
        if (close == null) return false;

        final InputConnection ic = (InputConnection) connection;
        final CharSequence sel;
        try {
            sel = ic.getSelectedText(0);
        } catch (Throwable tr) {
            return false;                       // 问不到就按老路走，不冒险
        }
        if (sel == null || sel.length() == 0) return false;
        if (sel.length() > 500) {                // 超大选区不重提交（避免卡顿），按老路走
            if (DEV_TRACE) Log.i(TAG, "pair wrap skipped: selection too long (" + sel.length() + ")");
            return false;
        }
        try {
            sInjecting.set(Boolean.TRUE);
            ic.beginBatchEdit();
            ic.commitText(String.valueOf(open) + sel + close, 1);   // 1 = 光标落在整串之后
            ic.endBatchEdit();
            if (close == open) {
                sLastToggleChar = open;
                sLastWasOpen = false;            // 包完这一对，下一个同字符又是"开"
            }
            if (DEV_TRACE) Log.i(TAG, "pair wrap: " + open + "…" + close + " around "
                    + sel.length() + " char(s)" + (hw ? " [hw]" : " [soft]"));
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "pair wrap failed: " + tr);
            return false;                        // 失败就让原提交照常走
        } finally {
            sInjecting.set(Boolean.FALSE);
        }
    }

    /**
     * **成对符号提交前**的总入口：先看要不要"只移光标"（光标后已有闭字符），再看要不要包选区。
     *
     * <p>必须在 {@code chain.proceed()} **之前**调用：{@code getTextAfterCursor} 与
     * {@code getSelectedText} 都只有那一刻问得到。
     *
     * <p>为什么这里只管"跳过"、不管"注入"：注入必须发生在开字符**上屏之后**
     * （见 {@link #maybeInject}），而跳过必须发生在上屏**之前** —— 顺序相反，只能分开。
     *
     * @return true = 已处理（调用方**不要**再走原提交）
     */
    static boolean maybeSkipClose(final Object connection, final CharSequence committed) {
        if (connection == null || committed == null || committed.length() != 1) return false;
        if (!(connection instanceof InputConnection)) return false;
        final boolean hw = sHwKey;
        if (hw ? !(sPhysEnabled && GboardState.physComplete()) : !sEnabled) return false;

        final char c = committed.charAt(0);
        final Character meta = sMap.get(c);
        // 这次按下去要上屏的闭字符：自己不是开字符时（纯闭字符，如 ）】」）就是它自己
        final char wantCloser = meta != null ? meta : c;
        // 同字符对（引号）不参与：开 == 闭，翻转交给 Gboard／我们自己的 sLastWasOpen
        if (meta != null && meta == c) return false;

        if (sJustPaired != 0 && sJustPaired == wantCloser) {
            final InputConnection ic = (InputConnection) connection;
            final CharSequence after;
            try {
                after = ic.getTextAfterCursor(1, 0);
            } catch (Throwable tr) {
                return false;
            }
            final int at = caretOffset(ic);
            if (after != null && after.length() == 1 && after.charAt(0) == wantCloser && at >= 0) {
                try {
                    ic.setSelection(at + 1, at + 1);
                    sJustPaired = 0;
                    sOwnCaret = at + 1;
                    if (DEV_TRACE) Log.i(TAG, "pair closeSkip: caret only -> " + (at + 1));
                    return true;
                } catch (Throwable tr) {
                    Log.w(TAG, "pair closeSkip failed: " + tr);
                }
            }
        }
        sJustPaired = 0;                        // 按下闭字符 ⇒ 无条件清位，避免残留
        return false;
    }

    /**
     * 选区变了：**用户把光标点到别处 ⇒ 上一次补全作废**（之后再打闭字符就直接出字）。
     *
     * <p>与搜狗同一套语义（那条是用户 2026-09-18 定的口径）。区分"用户点的"与"我们自己挪的"：
     * 第一次回调记作我们自己的落点，之后位置不同才算用户点击。
     */
    static void onSelectionChanged(final int newStart, final int newEnd) {
        if (sJustPaired == 0) return;
        final int where = Math.max(newStart, newEnd);
        if (sOwnCaret < 0) {
            sOwnCaret = where;
            return;
        }
        if (where != sOwnCaret) {
            if (DEV_TRACE) Log.i(TAG, "pair closeSkip: caret moved " + sOwnCaret + " -> " + where);
            sJustPaired = 0;
            sOwnCaret = -1;
        }
    }

    /** 本次补出的闭字符（注入成功后由 {@link #maybeInject} 记录）。 */
    private static void markPaired(final char closer) {
        sJustPaired = closer;
        sOwnCaret = -1;
    }

    /**
     * 光标的绝对偏移；问不到或触顶时返回 -1（宁可不动，也不用假偏移把光标跳错地方）。
     */
    private static int caretOffset(InputConnection ic) {
        try {
            final CharSequence before = ic.getTextBeforeCursor(4096, 0);
            if (before == null || before.length() >= 4096) return -1;
            return before.length();
        } catch (Throwable tr) {
            return -1;
        }
    }

    /** 同字符对：上一次这个字符是"开"⇒ 这一次当"闭"（不注入，只让它自己上屏）。 */
    private static boolean consumesAsClose(char c) {
        if (sLastToggleChar == c && sLastWasOpen) {
            sLastWasOpen = false;
            return true;
        }
        return false;
    }

    /**
     * 光标左移一格（落在刚补上的闭字符之前）。
     *
     * <p>判据照搜狗：{@code getTextBeforeCursor} 的长度就是绝对偏移，但它有上限 ——
     * 一旦触顶说明不是全量，**宁可不动**。
     */
    private static void moveCursorLeftOne(InputConnection ic) {
        final int cap = 4096;
        final CharSequence before = ic.getTextBeforeCursor(cap, 0);
        if (before == null) return;
        if (before.length() >= cap) {
            if (DEV_TRACE) Log.i(TAG, "pair: cursor move skipped (before >= " + cap + ")");
            return;
        }
        final int pos = before.length() - 1;
        if (pos < 0) return;
        ic.setSelection(pos, pos);
    }
}
