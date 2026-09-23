package moe.lovefirefly.bzk.gboardext;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 语音引擎「配置文件」的数据层。
 *
 * <p><b>唯一识别码 = 脚本里的 {@code engine.id}</b>（用户口径）：导入时按它覆盖同名配置，
 * 卡片第二行显示的就是它。
 *
 * <p><b>存放</b>：模块 App 的 SharedPreferences（JSON 数组）。Gboard 进程里不读这份列表，
 * 只接收"当前选中那一个"的脚本正文（走配置广播）—— 这样广播体积恒定，且列表怎么改都不影响目标进程。
 *
 * <p><b>内置配置</b>：来自模块 APK 的 {@code assets/engines/index.json} + 同目录脚本。
 * 首次运行播种一次；之后**删除就是删除**（不复活），由「恢复内置配置」按钮显式还原。
 */
final class VoiceProfiles {

    private static final String TAG = "GboardExt";

    static final String K_LIST = "voiceProfiles";
    static final String K_SELECTED = GboardConfig.KEY_ENGINE;

    private static final Pattern P_ID =
            Pattern.compile("engine\\s*\\.\\s*id\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern P_LABEL =
            Pattern.compile("engine\\s*\\.\\s*label\\s*=\\s*[\"']([^\"']+)[\"']");

    /** 一个配置文件：脚本正文 + 从脚本里读出来的元信息。 */
    static final class Profile {
        String id = "";
        String label = "";
        String script = "";
        boolean builtin;
        /** 勾选 = 启用（用 checkbox，但互斥：同一时刻只有一个或零个）。 */
        boolean enabled;
        /** 脚本里 {@code engine.input.<key>} 声明出来的表单值（appid / token …）。 */
        final java.util.Map<String, String> config = new java.util.LinkedHashMap<>();

        Profile() {
        }

        Profile(String id, String label, String script, boolean builtin) {
            this.id = id;
            this.label = label;
            this.script = script;
            this.builtin = builtin;
        }

        JSONObject toJson() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("script", script);
            o.put("builtin", builtin);
            o.put("enabled", enabled);
            final JSONObject c = new JSONObject();
            for (Map.Entry<String, String> e : config.entrySet()) c.put(e.getKey(), e.getValue());
            o.put("config", c);
            return o;
        }

        static Profile from(JSONObject o) {
            final Profile p = new Profile();
            p.id = o.optString("id", "");
            p.label = o.optString("label", "");
            p.script = o.optString("script", "");
            p.builtin = o.optBoolean("builtin", false);
            p.enabled = o.optBoolean("enabled", false);
            final JSONObject c = o.optJSONObject("config");
            if (c != null) {
                for (java.util.Iterator<String> it = c.keys(); it.hasNext(); ) {
                    final String k = it.next();
                    p.config.put(k, c.optString(k, ""));
                }
            }
            return p;
        }
    }

    private VoiceProfiles() {
    }

    // ------------------------------------------------------------------ 读写

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(GboardConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读列表；首次运行会把内置配置播种进去。 */
    static List<Profile> load(Context c) {
        final SharedPreferences sp = prefs(c);
        final String raw = sp.getString(K_LIST, null);
        if (raw == null) {
            final List<Profile> seeded = builtins(c);
            save(c, seeded);
            return seeded;
        }
        return parseList(raw);
    }

    static void save(Context c, List<Profile> list) {
        final JSONArray arr = new JSONArray();
        for (Profile p : list) {
            try {
                arr.put(p.toJson());
            } catch (Throwable ignored) {
            }
        }
        prefs(c).edit().putString(K_LIST, arr.toString()).apply();
    }

    static List<Profile> parseList(String raw) {
        final List<Profile> out = new ArrayList<>();
        try {
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Profile.from(arr.getJSONObject(i)));
            }
        } catch (Throwable tr) {
            Log.w(TAG, "profiles parse failed: " + tr);
        }
        return out;
    }

    static Profile find(List<Profile> list, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Profile p : list) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    /** 实际生效的那个配置（互斥勾选 ⇒ 最多一个；这里仍按"第一个勾选的"取，天然兼容）。 */
    static Profile effective(List<Profile> list) {
        for (Profile p : list) {
            if (p.enabled) return p;
        }
        return null;
    }

    static Profile effective(Context c) {
        return effective(load(c));
    }

    /**
     * 勾选/取消勾选。**互斥**：勾上某一个会把其它的自动取消 ⇒ 同一时刻只有「一个或零个」被勾选
     * （用户口径：不是多选）。取消勾选后如果都不剩，就退回原版 STT。
     */
    static void setEnabled(Context c, String id, boolean on) {
        final List<Profile> list = load(c);
        for (Profile p : list) {
            p.enabled = on && p.id.equals(id);
        }
        save(c, list);
        final Profile e = effective(list);
        prefs(c).edit().putString(K_SELECTED, e == null ? "" : e.id).apply();
    }

    // ------------------------------------------------------------------ 增删改

    /**
     * 导入一段脚本：id/label 从脚本里读（{@code engine.id} / {@code engine.label}）。
     *
     * @return 导入后的 id；失败返回 null（调用方给提示）
     */
    static String importScript(Context c, String script) {
        if (script == null || script.trim().isEmpty()) return null;
        final String id = extract(P_ID, script);
        if (id == null || id.isEmpty()) return null;          // 没有 engine.id 就不收
        final String label = extract(P_LABEL, script);
        final List<Profile> list = load(c);
        final Profile exist = find(list, id);
        if (exist != null) {
            exist.script = script;                             // 同名覆盖（用户口径）
            if (label != null && !label.isEmpty()) exist.label = label;
            Log.i(TAG, "profile overwritten: " + id);
        } else {
            list.add(new Profile(id, label == null || label.isEmpty() ? id : label,
                    script, false));   // 新导入的默认**不勾选**（导入后提示用户去勾）
            Log.i(TAG, "profile imported: " + id);
        }
        save(c, list);
        return id;
    }

    static void delete(Context c, String id) {
        final List<Profile> list = load(c);
        final List<Profile> keep = new ArrayList<>();
        for (Profile p : list) {
            if (!p.id.equals(id)) keep.add(p);
        }
        save(c, keep);
        final Profile e = effective(keep);
        prefs(c).edit().putString(K_SELECTED, e == null ? "" : e.id).apply();
    }

    /** 「恢复内置配置」：内置项缺失就补回、被改过就用 APK 里的版本覆盖；用户项不动。 */
    static void restoreBuiltins(Context c) {
        final List<Profile> list = load(c);
        final Map<String, Profile> byId = new LinkedHashMap<>();
        for (Profile p : list) byId.put(p.id, p);
        for (Profile b : builtins(c)) {
            final Profile old = byId.get(b.id);
            if (old != null) {
                // 还原的是**脚本与名称**，不该顺手把用户填的参数（appid/key…）和勾选状态清掉
                b.config.putAll(old.config);
                b.enabled = old.enabled;
            }
            byId.put(b.id, b);                                  // 覆盖 or 新增
        }
        save(c, new ArrayList<>(byId.values()));
    }

    /** 「强制更新配置文件」：重新从 APK 读取内置脚本（等价于 restore，但明确是"开发者改了脚本"的场景）。 */
    static void forceUpdate(Context c) {
        restoreBuiltins(c);
    }

    /** 导出成「id → 脚本」的 JSON（长按卡片复制的是单个脚本，这里给批量导出留口）。 */
    static String exportJson(Context c) {
        final JSONObject o = new JSONObject();
        try {
            for (Profile p : load(c)) o.put(p.id, p.script);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ engine.input

    /** 脚本里声明的表单字段名（{@code engine.input.<key> = …}），按出现顺序。 */
    private static final Pattern P_INPUT = Pattern.compile(
            "engine\\s*\\.\\s*input\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*"
                    + "(?:\"([^\"]*)\"|'([^']*)'|([^;\\n]*))");

    static List<String> inputKeys(String script) {
        final List<String> out = new ArrayList<>();
        if (script == null) return out;
        final Matcher m = P_INPUT.matcher(script);
        while (m.find()) {
            if (!out.contains(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    /** 声明时写的默认值（当占位符用）。 */
    static String inputDefault(String script, String key) {
        if (script == null) return "";
        final Matcher m = P_INPUT.matcher(script);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                final String v = m.group(2) != null ? m.group(2)
                        : (m.group(3) != null ? m.group(3) : m.group(4));
                return v == null ? "" : v.trim();
            }
        }
        return "";
    }

    /** 保存表单值（并落盘）。 */
    static void setConfig(Context c, String id, Map<String, String> values) {
        final List<Profile> list = load(c);
        final Profile p = find(list, id);
        if (p == null) return;
        p.config.clear();
        if (values != null) p.config.putAll(values);
        save(c, list);
    }

    /** 导出某个配置的表单值（广播用）。 */
    static String configJson(Profile p) {
        final JSONObject o = new JSONObject();
        if (p != null) {
            try {
                for (Map.Entry<String, String> e : p.config.entrySet()) {
                    o.put(e.getKey(), e.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ 内置

    /** 内置配置：assets/engines/index.json 里的每一项 + 同目录脚本正文。 */
    static List<Profile> builtins(Context c) {
        final List<Profile> out = new ArrayList<>();
        final String index = readAsset(c, "engines/index.json");
        if (index == null) {
            Log.w(TAG, "内置配置清单读不到");
            return out;
        }
        try {
            final JSONArray arr = new JSONObject(index).optJSONArray("engines");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject e = arr.getJSONObject(i);
                final String file = e.optString("script", "");
                final String script = readAsset(c, "engines/" + file);
                if (script == null) {
                    Log.w(TAG, "内置脚本缺失: " + file);
                    continue;
                }
                out.add(new Profile(e.optString("id", ""), e.optString("label", ""),
                        script, true));
            }
        } catch (Throwable tr) {
            Log.w(TAG, "内置配置解析失败: " + tr);
        }
        return out;
    }

    /** 从**模块自己的 APK** 读 assets（App 侧就是自己的 codePath）。 */
    static String readAsset(Context c, String path) {
        try {
            final String apk = c.getPackageManager()
                    .getApplicationInfo(c.getPackageName(), 0).sourceDir;
            try (ZipFile zf = new ZipFile(apk)) {
                final ZipEntry e = zf.getEntry("assets/" + path);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) {
                    final byte[] buf = new byte[(int) e.getSize()];
                    int off = 0;
                    while (off < buf.length) {
                        final int n = in.read(buf, off, buf.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                    return new String(buf, 0, off, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "读 asset 失败 " + path + ": " + tr);
            return null;
        }
    }

    private static String extract(Pattern p, String script) {
        final Matcher m = p.matcher(script);
        return m.find() ? m.group(1) : null;
    }
}
