package com.zifang.z.cache.common.protocol;

/**
 * Redis 取"一坨内存"文本的那把尺 —— 与 C 侧 {@code memtoll()} 同判据
 * （上游 5.0.14 {@code util.c:197-246}）。
 *
 * <p>
 * 为什么要有这么个类：{@code auto-aof-rewrite-min-size}、{@code maxmemory} 这些旋钮在上游
 * 走的都不是 {@code string2ll()}（{@link RedisIntegerFormat} 管的那一把），而是这一把带单位的
 * 文法 —— {@code CONFIG SET auto-aof-rewrite-min-size 100mb} 收，而
 * {@code getLongLongFromObject} 面对同一串会拒。用错尺的结果是"上游能写的配置我们拒"，
 * 或者反过来"上游拒的我们收下了一串谁也不认识的文本"。
 * </p>
 *
 * <p>
 * 三处与 {@link RedisIntegerFormat} <b>相反</b>的地方，都是从上游那四十行里逐条读出来的：
 * <ul>
 *   <li>前导零收（{@code "05mb"} = 5*1024*1024）：单位被剥掉之后剩下的是 {@code strtoll} 的活，
 *       而 {@code strtoll} 不吃 Redis 那套"合法的零只有一个字符"的语法（{@code util.c:235-243}）。</li>
 *   <li>正号不收（{@code "+1mb"} 拒）：跳数字那一圈只放过一个前置的 {@code '-'}（{@code :208}），
 *       于是 {@code "+1mb"} 的"单位"是整串 {@code "+1mb"}，不在表里。</li>
 *   <li>空串收，且收成 0（{@code ""}、{@code "b"}）：digits=0 → 交给 {@code strtoll("")} 得 0、
 *       {@code endptr} 还停在原地，那道 {@code *endptr != '\0'} 的闸就过去了（{@code :239-242}）。
 *       而只有一根负号的 {@code "-"} 反倒拒：{@code buf} 里是 {@code "-"}，{@code strtoll} 原地不动、
 *       {@code *endptr} 是那个 {@code '-'}。</li>
 * </ul>
 * 单位表全部不区分大小写，乘数是混的：{@code k/m/g} 是 1000 的幂，{@code kb/mb/gb} 是 1024 的幂
 * （{@code util.c:211-228}）—— 上游自己就这么混着，{@code 100k} 与 {@code 100kb} 差 24000 字节。
 * </p>
 *
 * <p>
 * <b>唯一一处故意不等价</b>：数字段溢出（{@code "99999999999999999999"}）在 C 侧是
 * {@code strtoll} 置 {@code ERANGE} 交出 {@code LLONG_MAX}，随后 {@code val*mul} 有符号溢出 ——
 * 那是未定义行为，实测到的只是"某个编译产物恰好绕成什么"，不是一条可抄的判据。
 * 这里一律判成"这一串不能当内存用"（返回 {@code null}），并在调用方落到上游那句
 * {@code Invalid argument '…' for CONFIG SET '…'}。
 * </p>
 */
public final class RedisMemoryFormat {

    private RedisMemoryFormat() {
    }

    /**
     * 按 {@code memtoll()} 的语法解析。
     *
     * @return 合法时返回字节数；语法不对或数字段溢出返回 {@code null}（对应 C 侧 {@code *err = 1}）
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        int n = text.length();
        int u = 0;
        // 上游只允许一个前置负号（util.c:208），第二个 '-' 会被算进"单位"那一段而落在表外。
        if (u < n && text.charAt(u) == '-') {
            u++;
        }
        int digitsEnd = u;
        while (digitsEnd < n && text.charAt(digitsEnd) >= '0' && text.charAt(digitsEnd) <= '9') {
            digitsEnd++;
        }
        long mul = multiplier(text.substring(digitsEnd));
        if (mul == 0) {
            return null;                              // 单位不在表里：k/mb/gb… 之外的任何写法
        }
        if (digitsEnd == 0) {
            // 连负号都没有（""、"b"、"kb"）：strtoll 交出 0 且原地不动，上游收。
            return Long.valueOf(0L);
        }
        long val;
        try {
            val = Long.parseLong(text.substring(0, digitsEnd));
        } catch (NumberFormatException e) {
            // 数字段自己溢出，或者只有一根负号（"-"、"-b" → buf 里是 "-"，strtoll 原地不动 → err）。
            return null;
        }
        try {
            return Long.valueOf(Math.multiplyExact(val, mul));
        } catch (ArithmeticException e) {
            return null;                              // 见类注释里那句"唯一一处故意不等价"
        }
    }

    /** 纯判据版本。 */
    public static boolean isMemoryText(String text) {
        return parse(text) != null;
    }

    /** 命中返回乘数，未命中返回 0 —— 与上游那串 {@code else { *err = 1; return 0; }} 同形。 */
    private static long multiplier(String unit) {
        if (unit.isEmpty() || unit.equalsIgnoreCase("b")) {
            return 1L;
        }
        if (unit.equalsIgnoreCase("k")) {
            return 1000L;
        }
        if (unit.equalsIgnoreCase("kb")) {
            return 1024L;
        }
        if (unit.equalsIgnoreCase("m")) {
            return 1000L * 1000;
        }
        if (unit.equalsIgnoreCase("mb")) {
            return 1024L * 1024;
        }
        if (unit.equalsIgnoreCase("g")) {
            return 1000L * 1000 * 1000;
        }
        if (unit.equalsIgnoreCase("gb")) {
            return 1024L * 1024 * 1024;
        }
        return 0L;
    }
}
