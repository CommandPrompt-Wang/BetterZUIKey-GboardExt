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
        if (hw ? !sPhysEnabled : !sEnabled) {             // 按来源挑开关
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
            }
            if (DEV_TRACE) Log.i(TAG, "pair: " + open + " -> " + open + close
                    + (hw ? " [hw]" : " [soft]"));
        } catch (Throwable tr) {
            Log.w(TAG, "pair inject failed: " + tr);
        } finally {
            sInjecting.set(Boolean.FALSE);
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
