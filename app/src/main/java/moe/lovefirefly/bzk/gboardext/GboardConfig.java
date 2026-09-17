package moe.lovefirefly.bzk.gboardext;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

/**
 * GboardExt 的配置：严格模式 + 「完整的 …… 和 ——」。
 *
 * <p>通道是**显式广播**（见 {@link BroadcastConfig}）—— provider 那条在 Gboard 上因包可见性
 * 走不通（ANALYSIS.md §15）。符号归一的表是硬编码的（{@link SymbolNorm}），不走配置。
 */
final class GboardConfig {

    static final String TAG = "GboardExt";
    static final String PREFS_NAME = "gboardext_config";
    static final String KEY_STRICT = "strict";
    static final String KEY_LONG = "longMarks";

    /** 严格模式：语言只由框架/BZK 决定（拦掉 Gboard 自己切布局/语言）。 */
    final boolean strict;

    /** 「完整的 …… 和 ——」：关 = 一个（搜狗原生），开 = 两个（中文排版标准）。 */
    final boolean longMarks;

    private GboardConfig(boolean strict, boolean longMarks) {
        this.strict = strict;
        this.longMarks = longMarks;
    }

    static GboardConfig defaults() {
        return new GboardConfig(true, true);
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, true),
                sp.getBoolean(KEY_LONG, true));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, true)
                + "&" + KEY_LONG + "=" + sp.getBoolean(KEY_LONG, true);
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = true;
        boolean longMarks = true;
        for (String kv : raw.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            final String k = kv.substring(0, i);
            final String v = kv.substring(i + 1);
            if (KEY_STRICT.equals(k)) strict = Boolean.parseBoolean(v);
            else if (KEY_LONG.equals(k)) longMarks = Boolean.parseBoolean(v);
        }
        return new GboardConfig(strict, longMarks);
    }

    String signature() {
        return "strict=" + strict + "|long=" + longMarks;
    }

    /** 模块侧读配置：优先 App 的 ContentProvider（这条在 Gboard 上走不通，留作兜底）。 */
    static String readProvider(ContentResolver cr) {
        if (cr == null) return null;
        try (Cursor c = cr.query(ConfigProvider.URI, null, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
            Log.d(TAG, "provider: empty cursor");
        } catch (Throwable err) {
            Log.d(TAG, "provider read failed: " + err);
        }
        return null;
    }
}
