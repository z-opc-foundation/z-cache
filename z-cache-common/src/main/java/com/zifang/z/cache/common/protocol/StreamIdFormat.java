package com.zifang.z.cache.common.protocol;

import java.math.BigInteger;

/**
 * Stream ID 的文法。唯一依据是上游 {@code t_stream.c} 5.0.14 的
 * {@code streamGenericParseIDOrReply}（:1174）与 {@code string2ull}（:1147）。
 *
 * <p>为什么不用 {@code RedisIntegerFormat} 一把尺：stream ID 的两段是 <b>uint64</b>，
 * 而 {@code string2ll} 只有 63 位。上游因此先试 {@code string2ll}、失败再退到
 * {@code strtoull}——这一退让两族的接受集变得不对称（见 {@link #strtoull}）。
 */
public final class StreamIdFormat {

    /** 上游 :1205-1206 的原文，一个字都不改。 */
    public static final String INVALID_ID =
            "Invalid stream ID specified as stream command argument";

    /** uint64 的全 1；Java 的 long 只能按位存下它。 */
    public static final long MAX_U64 = -1L;

    /** 上游 :1175-1176 的 {@code char buf[128]}：超过 127 字节直接判非法。 */
    static final int MAX_TEXT_LENGTH = 127;

    private static final BigInteger TEN = BigInteger.TEN;
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

    private StreamIdFormat() {
    }

    /**
     * @param missingSeq 没有 {@code -} 时补上的 seq：XRANGE / XPENDING 的终点位是
     *                   {@link #MAX_U64}，起点位与其它位置是 0（上游 :1356-1357、:2033-2035）
     * @param strict     XADD / XDEL / XACK / XCLAIM / XREAD / XGROUP CREATE 那一族，
     *                   单独的 {@code -} 与 {@code +} 在这些位置是非法 ID（上游 :1179-1180）
     * @return {@code {ms, seq}} 两个 uint64 的位模式；非法返回 null
     */
    public static long[] parse(String text, long missingSeq, boolean strict) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        if (strict && ("-".equals(text) || "+".equals(text))) {
            return null;
        }
        if ("-".equals(text)) {
            return new long[]{0L, 0L};
        }
        if ("+".equals(text)) {
            return new long[]{MAX_U64, MAX_U64};
        }
        int dash = text.indexOf('-');
        Long ms = string2ull(dash < 0 ? text : text.substring(0, dash));
        if (ms == null) {
            return null;
        }
        Long seq;
        if (dash < 0) {
            seq = missingSeq;
        } else {
            seq = string2ull(text.substring(dash + 1));
            if (seq == null) {
                return null;
            }
        }
        return new long[]{ms, seq};
    }

    /** 两个 uint64 位模式的字典序：先 ms 再 seq，都按无符号比。 */
    public static int compare(long ms1, long seq1, long ms2, long seq2) {
        int cmp = Long.compareUnsigned(ms1, ms2);
        return cmp != 0 ? cmp : Long.compareUnsigned(seq1, seq2);
    }

    /** 规范化写法，和上游 {@code addReplyStreamID} 一样由数值反推字符串。 */
    public static String format(long ms, long seq) {
        return Long.toUnsignedString(ms) + '-' + Long.toUnsignedString(seq);
    }

    /**
     * 一个 ID 的后继（上游 {@code streamIncrID}，:77-89）。XREAD / XREADGROUP 的 ID 位
     * 是"严格大于"（:1560 上游在那一行写的就是 "ID must be greater than this."），
     * 而上游的范围查询把起点当闭区间，于是它先在这里 +1 再交下去（:1602-1603）。
     *
     * <p>{@code MAX-MAX} 会<b>回绕成 {@code 0-0}</b>，不是停在原处——上游那个函数没有返回值，
     * 调用方拿到的就是回绕后的值。XREAD 这一侧观察不到它（:1591 的"必须真有条目更大"先挡住），
     * XREADGROUP 读历史没有那道闸，所以 {@code XREADGROUP … <MAX-MAX>} 交回的是整个历史。
     */
    public static long[] successor(long ms, long seq) {
        if (seq == MAX_U64) {
            return ms == MAX_U64 ? new long[]{0L, 0L} : new long[]{ms + 1L, 0L};
        }
        return new long[]{ms, seq + 1L};
    }

    /**
     * 上游 {@code string2ull}（:1147）：先试 {@code string2ll}，成功且非负即收；
     * 否则退到 {@code strtoull}。两条路的接受集不同，这不是实现疏忽，是上游的形状。
     */
    private static Long string2ull(String text) {
        Long signed = RedisIntegerFormat.parse(text);
        if (signed != null) {
            return signed < 0L ? null : signed;
        }
        return strtoull(text);
    }

    /**
     * {@code strtoull(s, &endptr, 10)} 加上上游那两道闸：{@code errno == ERANGE} 拒、
     * {@code *s != '\0' && *endptr == '\0'} 拒（:1157）。
     *
     * <p>于是它比 {@code string2ll} 宽出三类：前导空白、可选符号位、以及 2^63..2^64-1。
     * 而回绕是 C 的语义：{@code strtoull("-1")} 交回 2^64-1 且<b>不</b>置 errno，
     * 所以 {@code " -1"} 收、{@code "-1"} 反倒在 {@code string2ll} 那一步被"负数即越界"
     * 挡掉（:1150）。这一对不对称原样留着，不"顺手修正"。
     */
    private static Long strtoull(String text) {
        int i = 0;
        while (i < text.length() && isCSpace(text.charAt(i))) {
            i++;
        }
        if (i == text.length()) {
            return null;
        }
        boolean negative = false;
        char sign = text.charAt(i);
        if (sign == '+' || sign == '-') {
            negative = sign == '-';
            i++;
        }
        if (i == text.length()) {
            return null;
        }
        BigInteger acc = BigInteger.ZERO;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            acc = acc.multiply(TEN).add(BigInteger.valueOf(c - '0'));
            if (acc.compareTo(TWO_POW_64) >= 0) {
                return null;
            }
        }
        long bits = acc.longValue();
        return negative ? -bits : bits;
    }

    private static boolean isCSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }
}
