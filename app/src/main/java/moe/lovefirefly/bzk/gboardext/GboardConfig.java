package moe.lovefirefly.bzk.gboardext;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * GboardExt 的配置。设计照抄隔壁 SogouOEMExt：App 写 SharedPreferences →
 * ContentProvider 暴露一行 k=v&k=v → 模块每 2 秒轮询 + 签名比对（变了才动作）。
 *
 * <p>目前只有一个开关（严格模式）；符号映射表先占位，UI 下一轮补。
 */
final class GboardConfig {

    static final String TAG = "GboardExt";
    static final String PREFS_NAME = "gboardext_config";
    static final String KEY_STRICT = "strict";
    static final String KEY_TABLE = "symbolTable";

    /** 严格模式：语言只由框架/BZK 决定（拦掉 Gboard 自己切布局/语言）。 */
    final boolean strict;

    /** 全角→半角映射表（"前-后"成对，成对出现），留空 = 不做归一。 */
    final String symbolTable;

    private GboardConfig(boolean strict, String symbolTable) {
        this.strict = strict;
        this.symbolTable = symbolTable == null ? "" : symbolTable;
    }

    static GboardConfig defaults() {
        return new GboardConfig(true, "");
    }

    static GboardConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        return new GboardConfig(sp.getBoolean(KEY_STRICT, true), sp.getString(KEY_TABLE, ""));
    }

    static String dump(SharedPreferences sp) {
        return KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, true)
                + "&" + KEY_TABLE + "=" + enc(sp.getString(KEY_TABLE, ""));
    }

    static GboardConfig parseDump(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean strict = true;
        String table = "";
        for (String kv : raw.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            final String k = kv.substring(0, i);
            final String v = kv.substring(i + 1);
            if (KEY_STRICT.equals(k)) strict = Boolean.parseBoolean(v);
            else if (KEY_TABLE.equals(k)) table = dec(v);
        }
        return new GboardConfig(strict, table);
    }

    String signature() {
        return "strict=" + strict + "|tbl=" + symbolTable;
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

    static String enc(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            return URLEncoder.encode(raw, "UTF-8");
        } catch (Throwable err) {
            return "";
        }
    }

    static String dec(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Throwable err) {
            return encoded;
        }
    }
}
