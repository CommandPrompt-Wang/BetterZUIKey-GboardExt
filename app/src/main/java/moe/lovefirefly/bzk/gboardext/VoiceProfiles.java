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
        /**
         * 播种时那份脚本的指纹（MD5）。用来判断"用户改过没有"：
         * 没改过才敢让 {@link #syncBuiltins} 用 APK 里的新版本覆盖它。空串 = 老数据（没有指纹）。
         */
        String seeded = "";
        /**
         * 允许脚本连的域名/IP（**宿主会强制校验**，见 {@code ScriptEngine.JsWs}）。
         * 内置项来自 {@code index.json} 的 {@code hosts}；用户脚本默认空名单 ⇒ 不许联网，
         * 要在设置页（单击卡片）里显式填。空名单不是"不限制"，是"一个都不许"。
         */
        final java.util.List<String> hosts = new ArrayList<>();

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
            if (seeded != null && !seeded.isEmpty()) o.put("seeded", seeded);
            o.put("hosts", new JSONArray(hosts));
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
            p.seeded = o.optString("seeded", "");
            final JSONArray h = o.optJSONArray("hosts");
            if (h != null) {
                for (int i = 0; i < h.length(); i++) {
                    final String v = h.optString(i, "");
                    if (!v.isEmpty()) p.hosts.add(v);
                }
            }
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

    /** 读列表；首次运行会把内置配置播种进去，之后每次读都顺带把"没被改过的内置脚本"对齐到 APK 版本。 */
    static List<Profile> load(Context c) {
        final SharedPreferences sp = prefs(c);
        final String raw = sp.getString(K_LIST, null);
        if (raw == null) {
            final List<Profile> seeded = builtins(c);
            save(c, seeded);
            return seeded;
        }
        final List<Profile> list = parseList(raw);
        if (syncBuiltins(c, list)) save(c, list);
        return list;
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
            exist.hosts.clear();                               // 白名单跟着脚本走（脚本里没声明 ⇒ 空 = 不许联网）
            exist.hosts.addAll(hostsOf(script));
            Log.i(TAG, "profile overwritten: " + id);
        } else {
            final Profile np = new Profile(id, label == null || label.isEmpty() ? id : label,
                    script, false);   // 新导入的默认**不勾选**（导入后提示用户去勾）
            np.hosts.addAll(hostsOf(script));
            list.add(np);
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

    // ------------------------------------------------------------------ 白名单（hosts）

    /** 脚本里的 {@code engine.hosts = ["a","b"]} 声明（用户脚本用这个"自带"白名单）。 */
    private static final Pattern P_HOSTS = Pattern.compile(
            "engine\\s*\\.\\s*hosts\\s*=\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);
    private static final Pattern P_QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    /** 从脚本里读 {@code engine.hosts}（没有就空 = 不许联网）。 */
    static List<String> hostsOf(String script) {
        final List<String> out = new ArrayList<>();
        if (script == null) return out;
        final Matcher m = P_HOSTS.matcher(script);
        if (!m.find()) return out;
        final Matcher q = P_QUOTED.matcher(m.group(1));
        while (q.find()) addHost(out, q.group(1));
        return out;
    }

    /**
     * 设置页里填的域名串 → 列表。容错：逗号/空格/换行分隔；粘 {@code wss://host/path} 这种整串
     * 也能认（自动剥掉协议、端口、路径）。
     */
    static List<String> normalizeHosts(String text) {
        final List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,，\\s]+")) {
            addHost(out, part);
        }
        return out;
    }

    private static void addHost(List<String> out, String raw) {
        if (raw == null) return;
        String h = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.isEmpty()) return;
        final int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        final int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        if (h.startsWith("[")) {                       // IPv6 字面量 [::1]:443
            final int end = h.indexOf(']');
            if (end > 0) h = h.substring(1, end);
        } else {
            final int colon = h.indexOf(':');
            if (colon >= 0 && h.indexOf(':', colon + 1) < 0) h = h.substring(0, colon);
        }
        if (!h.isEmpty() && !out.contains(h)) out.add(h);
    }

    /** 广播用：域名列表 → JSON 数组串。 */
    static String hostsJson(Profile p) {
        return p == null ? "[]" : new JSONArray(p.hosts).toString();
    }

    /** 保存白名单（单击卡片的表单里那行「可访问域名」）。 */
    static void setHosts(Context c, String id, List<String> hosts) {
        final List<Profile> list = load(c);
        final Profile p = find(list, id);
        if (p == null) return;
        p.hosts.clear();
        if (hosts != null) p.hosts.addAll(hosts);
        save(c, list);
    }

    /** 内置脚本的"出厂白名单"（设置页里给个提示：这是 index.json 声明的）。 */
    static List<String> builtinHosts(Context c, String id) {
        for (Profile b : builtins(c)) {
            if (b.id.equals(id)) return b.hosts;
        }
        return new ArrayList<>();
    }

    /**
     * 内置项在 {@code index.json} 里声明的表单字段元信息（{@code key → {label, secret}}）。
     *
     * <p><b>哪些字段由脚本说了算</b>（{@code engine.input.*} 的声明顺序就是表单顺序），
     * 这里只补"怎么显示"：标签用声明里的 {@code label}（讯飞控制台是 APPID / APISecret / APIKey，
     * 直接照抄比小写键名好看），密文标记用 {@code secret}。用户脚本没有这份声明 ⇒ 返回空表，
     * 由调用方退回"键名 + 按名字猜"。
     */
    static Map<String, JSONObject> formMeta(Context c, String id) {
        final Map<String, JSONObject> out = new LinkedHashMap<>();
        try {
            final String index = readAsset(c, "engines/index.json");
            if (index == null) return out;
            final JSONArray arr = new JSONObject(index).optJSONArray("engines");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject e = arr.getJSONObject(i);
                if (!id.equals(e.optString("id", ""))) continue;
                final JSONArray cfg = e.optJSONArray("config");
                if (cfg != null) {
                    for (int j = 0; j < cfg.length(); j++) {
                        final JSONObject f = cfg.getJSONObject(j);
                        final String k = f.optString("key", "");
                        if (!k.isEmpty()) out.put(k, f);
                    }
                }
                break;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "读内置表单声明失败: " + tr);
        }
        return out;
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
                final Profile fresh = out.get(out.size() - 1);
                fresh.seeded = md5(script);                        // 播种指纹
                final JSONArray hs = e.optJSONArray("hosts");      // 白名单（宿主会强制）
                if (hs != null) {
                    for (int j = 0; j < hs.length(); j++) {
                        final String v = hs.optString(j, "");
                        if (!v.isEmpty()) fresh.hosts.add(v);
                    }
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "内置配置解析失败: " + tr);
        }
        return out;
    }

    /**
     * 内置脚本**随模块更新**（只动"没被用户改过"的那些）。
     *
     * <p><b>为什么需要</b>：内置配置是"播种一次"，APK 里的脚本改了，用户存的那份不会自己变 ——
     * 于是每改一次脚本都得手动点一次「恢复内置配置」，很容易忘（本模块的脚本已经改过两轮，
     * 两次都踩到：改了代码却还是旧脚本在跑，排查半天）。
     *
     * <p><b>规则</b>：
     * <ul>
     *   <li>没改过（{@code md5(script) == seeded}）⇒ 换成 APK 里的版本，并更新指纹；</li>
     *   <li>用户改过（指纹对不上）⇒ <b>一律不动</b>（子页会标「已修改」），要覆盖得自己点
     *       「恢复内置配置」；</li>
     *   <li>老数据没有指纹（本次改动之前播种的）⇒ 当作没改过，更新一次并补上指纹。
     *       代价是"老数据 + 手工改过"会被覆盖一次 —— 这与「恢复内置配置」原本的行为一致，
     *       没有新增风险；</li>
     *   <li>已被删除的内置项<b>不复活</b>（用户口径：删掉就是删掉，只有「恢复内置配置」能补回）。</li>
     * </ul>
     *
     * <p>填好的参数（{@code config}）和勾选状态一律保留：只换脚本正文与名称。
     *
     * @return 是否有变化（需要落盘）
     */
    private static boolean syncBuiltins(Context c, List<Profile> list) {
        boolean changed = false;
        try {
            for (Profile b : builtins(c)) {
                final Profile p = find(list, b.id);
                if (p == null) continue;                       // 删掉的不复活
                final boolean pristine = p.seeded == null || p.seeded.isEmpty()
                        || p.seeded.equals(md5(p.script));
                if (!pristine) continue;                       // 用户改过 ⇒ 不碰
                if (!b.script.equals(p.script)) {
                    p.script = b.script;                       // config / enabled 不动
                    changed = true;
                    Log.i(TAG, "内置脚本已更新: " + p.id + " (" + b.script.length() + " 字符)");
                }
                if (b.label != null && !b.label.isEmpty() && !b.label.equals(p.label)) {
                    p.label = b.label;
                    changed = true;
                }
                // 白名单也归模块管（内置脚本能连哪儿由 index.json 说了算）
                if (!b.hosts.equals(p.hosts)) {
                    p.hosts.clear();
                    p.hosts.addAll(b.hosts);
                    changed = true;
                }
                // 指纹本身也要落盘：老数据没指纹、或指纹过期，只要不写回去，下次读还是"判不出来"
                // （踩过：脚本内容恰好已是最新时只补了指纹没标 changed ⇒ 指纹永远存不下来）
                if (!b.seeded.equals(p.seeded)) {
                    p.seeded = b.seeded;
                    changed = true;
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "内置脚本同步失败: " + tr);
        }
        return changed;
    }

    /** 这个内置项是否被用户改过（子页据此标「已修改」，也解释"为什么它没自动更新"）。 */
    static boolean modified(Profile p) {
        return p != null && p.builtin && p.seeded != null && !p.seeded.isEmpty()
                && !p.seeded.equals(md5(p.script));
    }

    /** MD5（十六进制小写）。只用来做"改没改过"的指纹，不涉及安全。 */
    private static String md5(String s) {
        if (s == null) return "";
        try {
            final byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable tr) {
            return "";
        }
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
