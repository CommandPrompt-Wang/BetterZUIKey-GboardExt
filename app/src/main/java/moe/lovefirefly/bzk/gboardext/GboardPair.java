package moe.lovefirefly.bzk.gboardext;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配对表：**开字符 → 闭字符**（照搜狗模块那份默认表的 18 对）。
 *
 * <p>用 Map 而不是每次扫字符串：查表 O(1)，而且**方向性由结构本身保证** ——
 * 闭字符根本不是 key，所以"打闭字符却补出开字符"这类问题不会发生。
 *
 * <p>表串格式与搜狗一致：相邻两字符一组，**换行只是给人看的分组**（解析前先去掉），
 * 所以"只改分组换行"不会影响配对结果。空表 = 不配对。
 */
final class GboardPair {

    /** 去掉分组换行（解析、签名、UI 校验三处共用同一份清洗）。 */
    static final String DEFAULT_TABLE =
            "()[]{}（）［］｛｝＜＞\n"
            + "【】《》〈〉「」『』〖〗〔〕\n"
            + "\"\"''“”‘’";

    private GboardPair() {}

    /** 换行只是分组，别占配对位（只去 \r\n，不动空格 —— 空格也许是用户真想配的字符）。 */
    static String clean(String raw) {
        return raw == null ? "" : raw.replace("\r", "").replace("\n", "");
    }

    static Map<Character, Character> parse(String raw) {
        final Map<Character, Character> m = new LinkedHashMap<>();
        final String t = clean(raw);
        for (int i = 0; i + 1 < t.length(); i += 2) {
            m.putIfAbsent(t.charAt(i), t.charAt(i + 1));   // 首个出现的开字符生效
        }
        return Collections.unmodifiableMap(m);
    }

    /** dump/parseDump 的行格式是 k=v&k=v，表里可能出现 & 与 = ⇒ 必须转义。 */
    static String encode(String raw) {
        try {
            return URLEncoder.encode(clean(raw), "UTF-8");
        } catch (Throwable tr) {
            return "";
        }
    }

    static String decode(String enc) {
        try {
            return URLDecoder.decode(enc == null ? "" : enc, "UTF-8");
        } catch (Throwable tr) {
            return "";
        }
    }

    /** 自检：表串清洗后必须偶数长（奇数会把最后一对切坏，而解析是静默丢弃的）。 */
    static String selfCheck(String raw) {
        final String flat = clean(raw);
        if (flat.length() % 2 != 0) return "ODD LENGTH (" + flat.length() + ")";
        final Map<Character, Character> m = parse(flat);
        if (m.size() != flat.length() / 2) {
            return "DUPLICATE OPENER (" + m.size() + " < " + (flat.length() / 2) + ")";
        }
        return "ok (" + m.size() + " pairs)";
    }
}
