package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 顺序轮转：**不拦按键，而是接管 Gboard 自己的切换**（plan.md P5）。
 *
 * <p>Gboard 原生只认"最近使用的两个"（MRU）。开关打开后，凡是 Gboard 在本输入法内
 * 切语言（地球键 / 空格长按 / Ctrl+Space / 语言列表）都会被 {@link SwitchGuard} 看到 ——
 * 我们在这里把它改成"按用户排的顺序切下一个"，然后让原来的调用变成空操作。
 *
 * <p>为什么走这条：这是**唯一**一条软键盘、物理键、语言列表都汇合的路径
 * （§1 锚点表："谁调 switchToNextInputMethod / setInputMethodAndSubtype，谁就是语言切换器"），
 * 不用写死任何混淆名，也不用逐个按键去猜。
 *
 * <p>两种情形都覆盖：严格模式开着（原本要拦死）与关着（原本放行 MRU）——
 * 接管判定放在严格模式判定**之前**。
 */
final class Rotation {

    private static final String TAG = "GboardExt";

    private static volatile boolean sEnabled;
    private static volatile int[] sOrder = new int[0];
    private static volatile Context sCtx;
    private static volatile Object sService;

    /**
     * 最后已知的"当前 subtype hash"。
     *
     * <p>**不要**每次去查 {@code InputMethodManager.getCurrentInputMethodSubtype()}：
     * 实测在输入法自己的进程里它是**旧的**（连续三次切换日志都算出同一对
     * {@code 24c738a3 -> b16ade3b}，永远轮不到第三个语言）。权威来源是框架推给 IME 的
     * {@code onCurrentInputMethodSubtypeChanged}（{@link ServiceProbe#learnLang} 转过来），
     * 我们自己切成功后再补一刀，保证立刻前进。
     */
    private static volatile int sCurrent = Integer.MIN_VALUE;

    static void setCurrentHash(int h) {
        sCurrent = h;
    }

    /** 这次切换是**我们自己**发起的 ⇒ 守卫放行，且不要再次接管（防递归）。 */
    private static final ThreadLocal<Boolean> sOurs =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    static boolean ours() {
        return Boolean.TRUE.equals(sOurs.get());
    }

    private Rotation() {}

    static void attach(Context ctx, Object service) {
        if (ctx != null) sCtx = ctx;
        if (service != null) sService = service;
    }

    static void setEnabled(boolean on) {
        if (sEnabled != on) Log.i(TAG, "overrideRotation -> " + on);
        sEnabled = on;
    }

    static void setOrder(String csv) {
        final int[] a = RotationOrder.parse(csv);
        sOrder = a;
        Log.i(TAG, "rotation order: " + a.length + " entr(ies) [" + RotationOrder.format(a) + "]");
    }

    /**
     * 尝试接管这一次"切语言"。接管成功返回 true（调用方把它当已处理，不再走原路）。
     *
     * <p>认不出、切不动、只有一个语言 —— 一律返回 false，让 Gboard 照原样走（宁可退回原生，
     * 也不要卡住用户的切换键）。
     */
    static boolean takeOver() {
        if (!sEnabled || sCtx == null || ours()) return false;
        try {
            final InputMethodManager imm = (InputMethodManager)
                    sCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return false;
            final InputMethodInfo imi = findTarget(imm);
            if (imi == null) return false;
            final List<InputMethodSubtype> subs = imm.getEnabledInputMethodSubtypeList(imi, true);
            if (subs == null || subs.isEmpty()) return false;

            // 只把"真语言"放进链：没有 locale/languageTag 的是 Gboard 自带的隐式默认键盘
            // （extra=TrySuppressingImeSwitcher，见 ANALYSIS §2）。框架自己的 subtype 轮转也不带它，
            // 而且切过去 Gboard 内部语言并不跟随 ⇒ 放进链里只会"看着排了却不生效"。
            final List<Integer> usable = new ArrayList<>();
            for (InputMethodSubtype st : subs) {
                if (!label(st).isEmpty()) usable.add(st.hashCode());
            }
            final int[] avail = new int[usable.size()];
            for (int i = 0; i < avail.length; i++) avail[i] = usable.get(i);
            int curHash = sCurrent;
            if (curHash == Integer.MIN_VALUE) {          // 还不知道 ⇒ 退回 IMM（至少比没有强）
                final InputMethodSubtype cur = imm.getCurrentInputMethodSubtype();
                curHash = cur == null ? Integer.MIN_VALUE : cur.hashCode();
            }

            final int next = RotationOrder.pickNext(avail, sOrder, curHash);
            if (next == RotationOrder.NO_NEXT) return false;
            if (next == curHash) return false;

            InputMethodSubtype target = null;
            for (InputMethodSubtype st : subs) {
                if (st.hashCode() == next) {
                    target = st;
                    break;
                }
            }
            if (target == null) return false;

            final boolean ok = perform(imi, target);
            if (ok) sCurrent = next;                     // 立刻前进，不等框架回调
            Log.i(TAG, "rotation: " + Integer.toHexString(curHash) + " -> "
                    + Integer.toHexString(next) + " (order=" + sOrder.length
                    + ", avail=" + avail.length + ") " + (ok ? "ok" : "FAILED"));
            return ok;
        } catch (Throwable tr) {
            Log.w(TAG, "rotation failed: " + tr);
            return false;
        }
    }

    private static InputMethodInfo findTarget(InputMethodManager imm) {
        final List<InputMethodInfo> list = imm.getInputMethodList();
        if (list != null) {
            for (InputMethodInfo imi : list) {
                if (BridgeHook.TARGET_PKG.equals(imi.getPackageName())) return imi;
            }
        }
        return null;
    }

    /**
     * 真正执行切换：优先用输入法自己那条 {@code InputMethodService.switchInputMethod(id, subtype)}
     * （框架方法名，不涉及混淆名；模块就在 Gboard 进程里，调用方身份天然正确），
     * 退一步用 {@code InputMethodManager.setCurrentInputMethodSubtype(subtype)}。
     */
    private static boolean perform(InputMethodInfo imi, InputMethodSubtype subtype) {
        sOurs.set(Boolean.TRUE);
        try {
            final Object svc = sService;
            if (svc != null) {
                try {
                    final Method m = svc.getClass().getMethod("switchInputMethod",
                            String.class, InputMethodSubtype.class);
                    m.setAccessible(true);
                    m.invoke(svc, imi.getId(), subtype);
                    return true;
                } catch (Throwable tr) {
                    Log.i(TAG, "rotation: service path failed (" + tr + "), try imm");
                }
            }
            final InputMethodManager imm = (InputMethodManager)
                    sCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                final Method m = InputMethodManager.class.getMethod(
                        "setCurrentInputMethodSubtype", InputMethodSubtype.class);
                m.setAccessible(true);
                m.invoke(imm, subtype);
                return true;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "rotation perform failed: " + tr);
        } finally {
            sOurs.set(Boolean.FALSE);
        }
        return false;
    }

    // ------------------------------------------------------------------ 给 App 侧数顺序用

    /** 当前可用的 subtype（hash + 人类可读标签），App 的排序页用它（空列表 = 读不到）。 */
    static List<String[]> availableSubtypes() {
        final List<String[]> out = new ArrayList<>();
        final Context ctx = sCtx;
        if (ctx == null) return out;
        try {
            final InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            final InputMethodInfo imi = findTarget(imm);
            if (imi == null) return out;
            final List<InputMethodSubtype> subs = imm.getEnabledInputMethodSubtypeList(imi, true);
            if (subs == null) return out;
            for (InputMethodSubtype st : subs) {
                out.add(new String[]{String.valueOf(st.hashCode()), label(st)});
            }
        } catch (Throwable tr) {
            Log.w(TAG, "availableSubtypes failed: " + tr);
        }
        return out;
    }

    /** 空串 = 隐式/无标签（不是一门可轮转的语言）。 */
    private static String label(InputMethodSubtype st) {
        final String tag = st.getLanguageTag();
        final String loc = st.getLocale();
        final String base = (tag != null && !tag.isEmpty()) ? tag
                : (loc == null ? "" : loc);
        return base + (st.getMode() == null || st.getMode().isEmpty() ? "" : " / " + st.getMode());
    }
}
