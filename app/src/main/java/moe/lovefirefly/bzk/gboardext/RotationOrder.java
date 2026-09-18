package moe.lovefirefly.bzk.gboardext;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮转顺序的**纯逻辑**（不碰 Android，方便 JVM 单测）。
 *
 * <p>"下一个"怎么算：
 * <ol>
 *   <li>把配置顺序里**当前可用**的 subtype 挑出来，排成一条链；</li>
 *   <li>配置里没提到但当前可用的，**接在链尾**（否则那些语言会被永久跳过）；</li>
 *   <li>顺序表为空 ⇒ 直接用框架给的自然顺序；</li>
 *   <li>当前不在链上 ⇒ 从链首开始；只有一个（或零个）⇒ 不切。</li>
 * </ol>
 *
 * <p>用 subtype 的 {@code hashCode()} 当 id（与模块学习语言时用的是同一套 id）——
 * Gboard 升级后 hash 可能变，认不出的会被自动跳过，不会乱切。
 */
final class RotationOrder {

    /** 没有下一个（只有一个语言 / 认不出任何可用项）。 */
    static final int NO_NEXT = Integer.MIN_VALUE;

    private RotationOrder() {}

    /** 配置串：逗号分隔的 hash（空 = 框架顺序）。 */
    static int[] parse(String csv) {
        if (csv == null || csv.isEmpty()) return new int[0];
        final List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            final String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(t));
            } catch (Throwable ignored) {
                // 认不出的项直接丢（升级/手改配置都可能有）
            }
        }
        final int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static String format(int[] hashes) {
        final StringBuilder sb = new StringBuilder();
        for (int h : hashes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(h);
        }
        return sb.toString();
    }

    /** 按顺序表算出"下一个"要切到哪个 hash；没有就返回 {@link #NO_NEXT}。 */
    static int pickNext(int[] available, int[] order, int current) {
        if (available == null || available.length <= 1) return NO_NEXT;
        final List<Integer> chain = buildChain(available, order);
        if (chain.size() <= 1) return NO_NEXT;
        final int idx = chain.indexOf(current);
        if (idx < 0) return chain.get(0);
        return chain.get((idx + 1) % chain.size());
    }

    /** 顺序表里可用的 → 链；顺序表没提到但可用的 → 接尾。 */
    static List<Integer> buildChain(int[] available, int[] order) {
        final List<Integer> chain = new ArrayList<>();
        if (order != null) {
            for (int h : order) {
                if (contains(available, h) && !chain.contains(h)) chain.add(h);
            }
        }
        for (int h : available) {
            if (!chain.contains(h)) chain.add(h);
        }
        return chain;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }
}
