package moe.lovefirefly.bzk.gboardext;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 顺序轮转的排序页：把 Gboard 已启用的语言按你要的顺序排好。
 *
 * <p>为什么 App 自己列：模块（跑在 Gboard 进程里）当然能看到这些 subtype，但没有"模块 → App"
 * 的通道；反过来 App 读 Gboard 的 subtype 列表需要**包可见性**（清单里的 `<queries>`，
 * 这也是 ZUI 那条"读取应用列表"标签的来源 —— 用户已确认不管它）。
 *
 * <p>顺序存成 subtype 的 `hashCode` 逗号串（与模块学习语言用的同一套 id）；Gboard 升级后
 * hash 可能变，模块侧认不出的项会自动跳过、不会乱切。
 */
public class RotationActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private final List<String[]> available = new ArrayList<>();   // {hash, label}
    private final List<Integer> order = new ArrayList<>();
    private LinearLayout listBox;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(GboardConfig.PREFS_NAME, MODE_PRIVATE);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏与 BZK 其他页面同构：MaterialToolbar + 系统 up 箭头（子页面得能回去）
        final com.google.android.material.appbar.MaterialToolbar toolbar =
                new com.google.android.material.appbar.MaterialToolbar(this);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (56 * getResources().getDisplayMetrics().density)));
        toolbar.setElevation(4 * getResources().getDisplayMetrics().density);
        toolbar.setTitle("顺序轮转");
        toolbar.setTitleCentered(false);
        toolbar.setNavigationIcon(themeUpIndicator());
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        final TextView hint = new TextView(this);
        hint.setText("用 ↑ / ↓ 排序；没排到的接在最后。开启「覆盖默认轮转」后生效。");
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(pad * 2, pad, pad * 2, pad);
        root.addView(hint);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(pad * 2, 0, pad * 2, pad * 2);
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        MainActivity.applyInsets(root);

        loadAvailable();
        buildRows();
    }

    private int themeColor(int attrRes) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return androidx.core.content.ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }

    /** XML 里 `app:navigationIcon="?attr/homeAsUpIndicator"` 的代码版（与 BZK 页面同一套外观）。 */
    private android.graphics.drawable.Drawable themeUpIndicator() {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.homeAsUpIndicator, tv, true);
        if (tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId);
        }
        // 兜底：appcompat 自带的返回箭头（主题没定义该属性时才用）
        return androidx.core.content.ContextCompat.getDrawable(
                this, androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    }

    /** 枚举 Gboard 已启用的 subtype；读不到就留空并提示（可见性/权限问题都归这一类）。 */
    private void loadAvailable() {
        try {
            final android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            android.view.inputmethod.InputMethodInfo target = null;
            final List<android.view.inputmethod.InputMethodInfo> list = imm.getInputMethodList();
            for (android.view.inputmethod.InputMethodInfo imi : list) {
                if (BridgeHook.TARGET_PKG.equals(imi.getPackageName())) {
                    target = imi;
                    break;
                }
            }
            if (target != null) {
                final List<android.view.inputmethod.InputMethodSubtype> subs =
                        imm.getEnabledInputMethodSubtypeList(target, true);
                if (subs != null) {
                    for (android.view.inputmethod.InputMethodSubtype st : subs) {
                        available.add(new String[]{String.valueOf(st.hashCode()),
                                subtypeLabel(st)});
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 已存顺序里的 hash 先排进来（按存的顺序），其余按枚举顺序接上
        final int[] saved = RotationOrder.parse(prefs.getString(
                GboardConfig.KEY_ROTATION_ORDER, ""));
        for (int h : saved) {
            if (findAvailable(h) != null && !order.contains(h)) order.add(h);
        }
        for (String[] item : available) {
            final int h = Integer.parseInt(item[0]);
            if (!order.contains(h)) order.add(h);
        }
    }

    /**
     * subtype 的人类可读名。
     *
     * <p>Gboard 自带的默认 Latin(en-US) subtype **不带 locale / languageTag** ——
     * 不兜底就会显示成空白（用户报过）。它的识别特征是
     * {@code mode=keyboard} + {@code extra=TrySuppressingImeSwitcher}（见 ANALYSIS §2），
     * 所以这里明确标成"英文（无标签）"，并附 hash 便于区分多个无标签项。
     */
    /** 空串 = 无标签 subtype（Gboard 的英文就是这种，但照样能轮转）。 */
    private static String rawLabel(
            android.view.inputmethod.InputMethodSubtype st) {
        final String tag = st.getLanguageTag();
        final String loc = st.getLocale();
        return (tag != null && !tag.isEmpty()) ? tag : (loc == null ? "" : loc);
    }

    private static String subtypeLabel(
            android.view.inputmethod.InputMethodSubtype st) {
        String label = rawLabel(st);
        if (label.isEmpty()) {
            label = "（无标签，Gboard 的英文通常是这个）";
        }
        final String mode = st.getMode();
        if (mode != null && !mode.isEmpty()) label = label + " / " + mode;
        return label + "  ·  " + Integer.toHexString(st.hashCode());
    }

    private String[] findAvailable(int hash) {
        for (String[] item : available) {
            if (Integer.parseInt(item[0]) == hash) return item;
        }
        return null;
    }

    private void buildRows() {
        listBox.removeAllViews();
        if (available.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText("读不到 Gboard 的语言列表（需要包可见性 / 已启用的语言）。");
            empty.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyMedium);
            empty.setTextColor(themeColor(com.google.android.material.R.attr.colorError));
            listBox.addView(empty);
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            final int hash = order.get(i);
            final String[] item = findAvailable(hash);
            final String label = item == null ? ("未知 " + Integer.toHexString(hash)) : item[1];

            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad / 2, 0, pad / 2);

            final TextView tv = new TextView(this);
            tv.setText((i + 1) + ". " + label);
            tv.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyLarge);
            row.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final int idx = i;
            row.addView(arrowButton("↑", i > 0, () -> move(idx, idx - 1)));
            row.addView(arrowButton("↓", i < order.size() - 1, () -> move(idx, idx + 1)));
            listBox.addView(row);
        }
    }

    private com.google.android.material.button.MaterialButton arrowButton(
            String text, boolean enabled, Runnable onClick) {
        final com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setCornerRadius((int) (40 * getResources().getDisplayMetrics().density));
        b.setEnabled(enabled);
        b.setOnClickListener(v -> onClick.run());
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad / 2, 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void move(int from, int to) {
        if (to < 0 || to >= order.size()) return;
        final int v = order.remove(from);
        order.add(to, v);
        persist();
        buildRows();
    }

    /** 存顺序 + 立刻广播（顺序变了马上生效，不用重启输入法）。 */
    private void persist() {
        final int[] a = new int[order.size()];
        for (int i = 0; i < a.length; i++) a[i] = order.get(i);
        prefs.edit().putString(GboardConfig.KEY_ROTATION_ORDER,
                RotationOrder.format(a)).apply();
        ConfigSender.send(this, prefs);
        ConfigRetry.schedule(this);
        android.widget.Toast.makeText(this, "顺序已保存（立即生效）",
                android.widget.Toast.LENGTH_SHORT).show();
    }
}
