package moe.lovefirefly.bzk.gboardext;

import android.util.Log;
import android.view.View;

/**
 * 输入法窗口上的一行提示（热键切换后给个反馈）。
 *
 * <p>为什么不用 Toast：搜狗那边实测 Toast 会被系统按应用通知设置拦掉
 * （{@code NotificationService: Suppressing toast ... by user request}），
 * 所以照同一套做法 —— 自己在输入法窗口上加一个 {@link android.widget.PopupWindow}，
 * 贴底居中、约屏幕高 12%、1.2 秒后消失。
 *
 * <p>视图在 {@code setInputView} / {@code onCreateInputView} 时由 {@link ServiceProbe} 塞进来。
 */
final class Banner {

    private static final String TAG = "GboardExt";

    private static volatile View sView;
    private static volatile android.widget.PopupWindow sShowing;

    private Banner() {}

    static void attachView(View v) {
        if (v != null) sView = v;
    }

    /** 主线程调用（按键钩子本来就在主线程）。 */
    static void show(String text) {
        final View anchor = sView;
        if (anchor == null) {
            Log.i(TAG, "banner(no view): " + text);
            return;
        }
        try {
            final android.content.Context ctx = anchor.getContext();
            final float density = ctx.getResources().getDisplayMetrics().density;
            final int padH = (int) (14 * density);
            final int padV = (int) (7 * density);

            final android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(text);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xCC202020);
            tv.setPadding(padH, padV, padH, padV);

            final android.widget.PopupWindow pw = new android.widget.PopupWindow(tv,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
            pw.setOutsideTouchable(false);
            pw.setFocusable(false);
            final int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            pw.showAtLocation(anchor, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER,
                    0, (int) (screenH * 0.12f));
            if (sShowing != null) {
                try {
                    sShowing.dismiss();
                } catch (Throwable ignored) {
                }
            }
            sShowing = pw;
            anchor.postDelayed(() -> {
                try {
                    pw.dismiss();
                } catch (Throwable ignored) {
                }
                if (sShowing == pw) sShowing = null;
            }, 1200L);
        } catch (Throwable tr) {
            Log.w(TAG, "banner failed: " + tr);
        }
    }
}
