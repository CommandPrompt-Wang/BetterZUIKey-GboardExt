package moe.lovefirefly.bzk.gboardext;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;


/**
 * GboardExt 的配置。设计照抄隔壁 SogouOEMExt：App 写 SharedPreferences →
 * ContentProvider 暴露一行 k=v&k=v → 模块每 2 秒轮询 + 签名比对（变了才动作）。
 *
 * <p>目前只有一个配置：严格模式开关。通道是显式广播（见 {@link BroadcastConfig}），
 * provider 那条在 Gboard 上因包可见性走不通（ANALYSIS.md §15）。
 * 符号归一表是硬编码的（{@link SymbolNorm}），不走配置。
 */
final class GboardConfig {

    static final String TAG = "GboardExt";
    static final String PREFS_NAME = "gboardext_config";
    static final String KEY_STRICT = "strict";
    static final String KEY_LONG = "longMarks";
    static final String KEY_DUNHAO = "dunhao";

    /** 严格模式：语言只由框架/BZK 决定（拦掉 Gboard 自己切布局/语言）。 */
    final boolean strict;

    /** 「完整的 …… 和 ——」：关 = 一个（搜狗原生），开 = 两个（中文排版标准）。 */
    final boolean longMarks;

    /** 「顿号映射」：见 {@link SlashMap}（0 = \，1 = /，2 = 全部）。 */
    final int dunhao;

    private GboardConfig(boolean strict, boolean longMarks, int dunhao) {
        this.strict = strict;
        this.longMarks = longMarks;
        this.dunhao = dunhao;
    }

    static GboardConfig defaults() {
        return new GboardConfig(true, true, SlashMap.MODE_BACKSLASH);
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, true),
                sp.getBoolean(KEY_LONG, true),
                sp.getInt(KEY_DUNHAO, SlashMap.MODE_BACKSLASH));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, true)
                + "&" + KEY_LONG + "=" + sp.getBoolean(KEY_LONG, true)
                + "&" + KEY_DUNHAO + "=" + sp.getInt(KEY_DUNHAO, SlashMap.MODE_BACKSLASH);
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = true;
        boolean longMarks = true;
        int dunhao = SlashMap.MODE_BACKSLASH;
        for (String kv : raw.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            final String k = kv.substring(0, i);
            final String v = kv.substring(i + 1);
            if (KEY_STRICT.equals(k)) strict = Boolean.parseBoolean(v);
            else if (KEY_LONG.equals(k)) longMarks = Boolean.parseBoolean(v);
            else if (KEY_DUNHAO.equals(k)) dunhao = parseInt(v, SlashMap.MODE_BACKSLASH);
        }
        return new GboardConfig(strict, longMarks, dunhao);
    }

    static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    String signature() {
        return "strict=" + strict + "|long=" + longMarks + "|dunhao=" + dunhao;
    }

    /** 模块侧读配置：优先 App 的 ContentProvider（不依赖 XposedService）。 */
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
