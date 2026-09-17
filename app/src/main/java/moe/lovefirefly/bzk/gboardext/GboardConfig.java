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

    /** 严格模式：语言只由框架/BZK 决定（拦掉 Gboard 自己切布局/语言）。 */
    final boolean strict;

    private GboardConfig(boolean strict) {
        this.strict = strict;
    }

    static GboardConfig defaults() {
        return new GboardConfig(true);
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, true));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, true);
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = true;
        for (String kv : raw.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            if (KEY_STRICT.equals(kv.substring(0, i))) {
                strict = Boolean.parseBoolean(kv.substring(i + 1));
            }
        }
        return new GboardConfig(strict);
    }

    String signature() {
        return "strict=" + strict;
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
