package com.zifang.z.cache.common.protocol;

/**
 * Redis 取整数文本的那把尺 —— 与 C 侧 {@code string2ll()} 同判据。
 *
 * <p>
 * 为什么要有这么个类：{@code Integer.parseInt} / {@code Long.parseLong} 收得比 Redis 宽，
 * 而且宽得不止一处 —— 一个 {@code "+5"} 就能同时骗过下标、步长、过期秒数、键数四条路径。
 * 所以判据必须集中成一条，而不是散在几十个 {@code try/catch} 里各写各的。
 *
 * <p>
 * 三条实测来的规则（{@code battery37}，参考实例 redis-server 4.0.9，逐条对拍）：
 * <ul>
 *   <li>{@code "+5"} 非法。Java 的 {@code parseLong} 收正号，Redis 的解析器只认负号。</li>
 *   <li>{@code "-0"} 非法。注意这不是"值为 0 被范围判挡下"：{@code INCRBY k 0} 是合法的
 *       （回 {@code :5}），{@code EXPIRE k 0} 也是合法的（回 {@code :1} 并删键），
 *       只有带负号的那个 0 在<b>语法</b>这一档就死了。</li>
 *   <li>负号之后第一位必须是 {@code 1..9}，于是 {@code "05"}、{@code "00"} 一律非法，
 *       合法的零只有整串一个字符的 {@code "0"}。这一条对 Java 同样是反的。</li>
 * </ul>
 * 超范围（{@code 9223372036854775808}）与语法不对，在 Redis 侧共用一句文案
 * （{@code value is not an integer or out of range}），所以这里也不区分两种失败：
 * 返回 {@code null} 就是"这一串不能当整数用"。
 *
 * <p>
 * C 侧还有一条"长度 ≥ 32 直接拒"的快速通道。这里不复刻：32 位十进制必然先撞上
 * 范围检查，写出来是一段谁都打不到的死代码（等价变异），只留下这句话说明它存在过。
 */
public final class RedisIntegerFormat {

    private RedisIntegerFormat() {
    }

    /**
     * 按 Redis 的语法解析 64 位整数。
     *
     * @return 合法时返回其值；语法不对或超出 {@code [Long.MIN_VALUE, Long.MAX_VALUE]} 返回 {@code null}
     */
    public static Long parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int n = text.length();
        int i = 0;
        boolean negative = text.charAt(0) == '-';
        if (negative) {
            i = 1;
        }
        if (i == n) {
            return null;                              // 只有一个负号
        }
        char first = text.charAt(i);
        if (first == '0') {
            // 合法的零只有"整串就是 0"这一种写法："-0"、"05"、"00" 全在语法这一档被拒。
            return (!negative && n == 1) ? Long.valueOf(0L) : null;
        }
        if (first < '1' || first > '9') {
            return null;                              // "+5"、" 5"、"5 "、"0x10"、"1e2"、"5.0"
        }
        // 一律按负数累加：这样 |Long.MIN_VALUE| 那位绝对值溢不出去。
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long acc = 0;
        for (; i < n; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return null;
            }
            if (acc < limit / 10) {
                return null;
            }
            acc *= 10;
            if (acc < limit + digit) {
                return null;
            }
            acc -= digit;
        }
        return Long.valueOf(negative ? acc : -acc);
    }

    /** 与 {@link #parse} 同一把尺，但只接得住 int 的调用点（键数、个数、DB 序号）用。 */
    public static Integer parseAsInt(String text) {
        Long v = parse(text);
        if (v == null || v.longValue() < Integer.MIN_VALUE || v.longValue() > Integer.MAX_VALUE) {
            return null;
        }
        return Integer.valueOf(v.intValue());
    }

    /** 纯判据版本：给"只想知道合不合法"的调用点（例如键空间里存的整数值）。 */
    public static boolean isIntegerText(String text) {
        return parse(text) != null;
    }
}
