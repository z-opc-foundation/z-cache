package com.zifang.z.cache.common.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Redis 的浮点数字符串形状。
 * <p>
 * Redis 回复分数走的是 {@code %.17g} + 削掉小数点后的尾零（{@code d2string}），不是"最短可回读
 * 表示"。同一个 double 两者文本不同，而这串文本要进客户端解析：{@code ZADD k 0.1 m} 之后
 * {@code ZSCORE k m}，Redis 回 {@code 0.10000000000000001}，Java 的
 * {@code Double.toString} 回 {@code 0.1}；{@code +inf} 成员 Redis 回 {@code inf}，
 * {@code Double.toString} 回 {@code Infinity}。1.3.6 的在岸实测（同一台机器并排跑真
 * redis-server 4.0.9 与本实现）量到的 {@code 1.0} / {@code Infinity} 都出在这里。
 * <p>
 * Java 的 {@code String.format("%.17g", ...)} 也不能直接用：它的舍入与 glibc 不同，
 * 0.1 会得到 {@code 0.1}、1e-7 会得到 {@code 1e-07}。这里改成"先按 double 的精确值舍到
 * 17 位有效数字（HALF_EVEN），再按 {@code %g} 的进位阈值决定定点还是科学计数"。
 * <p>
 * 仓库里曾有四份各自 trimming 的副本（{@code CommandHandler} / {@code HashStore} /
 * {@code SortedSetStore} 各一份），其中只有两份削了结尾的小数点——同一产品对同一个值有三种
 * 答案。这里收成一个口子，两族各一个方法；两族的"回复"与"存进去的那串字节"在参考实现里逐例
 * 相同（250 实测 14 个输入 × 2 列一致），所以调用点不必再分两份。
 * <p>
 * 两族的分界不在形状而在<b>位宽</b>：{@code INCRBYFLOAT} 一族在 x86-64 上算的是 80 位
 * long double（64 位有效位），打印前还要还原成十进制；{@code ZSCORE} 一族算的是 64 位
 * double。同一个 {@code 0.1 + 0.2}，前者回 {@code 0.3}、后者回 {@code 0.30000000000000004}
 * （250 实测逐例一致）。早先把这一族记成"Java 追不上的精度差"，是因为在 double 里做加法再
 * 打印 —— 加法本身就丢了位。现在 {@link #plainSum} 按 long double 的规则算：十进制文本精确
 * 相加，舍到 {@code 2^(e-63)} 的二进制网格上（{@code e = floor(log2|v|)}，即 IEEE 的 64 位
 * 有效位），再按 {@code %.17Lf} 打。实测的 12 个探针（含 {@code 12345678901234567890123 →
 * 12345678901234567889920} 这种要还原二进制网格才对的）逐位复现。
 */
public final class RedisDoubleFormat {

    private static final MathContext SEVENTEEN = new MathContext(17, RoundingMode.HALF_EVEN);
    /** {@code %g} 的阈值：有效位数 17，指数小于 -4 或大于等于 17 才用科学计数。 */
    private static final int PRECISION = 17;
    /** {@code %.17Lf}：humanReadable 那一族小数点后的位数上限。 */
    private static final int MAX_PLAIN_FRACTION_DIGITS = 17;
    /** x87 {@code long double} 的有效位数（含隐含的那一位）。 */
    private static final int LDBL_MANTISSA_BITS = 64;
    /** 指数的上界：x87 的 {@code 2^16383}，不是 {@code 2^16384}（那已经是无穷那一侧）。 */
    private static final int LDBL_MAX_EXPONENT = 16383;
    /**
     * {@code LDBL_MAX = (2 - 2^-63) × 2^16383 ≈ 1.1897312545972400e4932}。
     * 越过它的文本 {@code strtold} 交出无穷，Redis 用 {@code isinf()} 当场拒
     * （250 实测 {@code HINCRBYFLOAT h f 1e4933 → value is not a valid float}，
     * 而 {@code 1.1e4932} 照收）；两数相加越过它则是无穷（实测
     * {@code 1e4932 + 1e4932 → "inf"} 并把这个值写进字段）。
     */
    private static final BigDecimal LDBL_MAX = powerOfTwo(LDBL_MAX_EXPONENT)
            .multiply(BigDecimal.valueOf(2).subtract(powerOfTwo(-(LDBL_MANTISSA_BITS - 1))));
    /** {@code LDBL_MAX} 的十进制首位指数（它是 1.1897e4932，所以首位指数正好是 4932）。 */
    private static final int LDBL_MAX_DEC_EXPONENT = 4932;
    /**
     * 最小的非零格点 {@code 2^-16445 ≈ 3.6452e-4951}：低于最小规格数 {@code 2^(1-16383) = 2^-16382}
     * 的值是 subnormal，格点步长不再随量级缩小，而是钉在这一格上。x87 的口径是
     * {@code 2^(1-EMAX-(P-1))}，{@code EMAX = 16383}、{@code P = 64}，也就是
     * {@code -(16383 + 64 - 2)}；写成 {@code -(...-1)} 会把网格做细一倍，界限跟着偏一半。
     */
    private static final int LDBL_SUBNORMAL_STEP_EXPONENT = -(LDBL_MAX_EXPONENT + LDBL_MANTISSA_BITS - 2);
    /**
     * 半个最小格点 {@code 2^-16446 ≈ 1.8226e-4951}：非零文本舍到这一格<b>以下</b>时，
     * {@code strtold} 交出 0 并置 {@code ERANGE}，而 {@code string2ld} 写的是
     * {@code errno = 0; ...; if (s == NULL || errno != 0) return C_ERR;} —— 于是"下溢到 0"
     * 在参考实现里是<b>错误</b>，不是静默的 0（这一条曾经记反，把 {@code 1e-4951} 收了下来）。
     * 250 实测（redis 4.0.9，battery23 三十九行；INCRBYFLOAT 与 HINCRBYFLOAT 两族同一把尺）：
     * {@code 1e-4951 / 1.8e-4951 / 1e-4952 / 2e-4952 / 1e-9999 → value is not a valid float}，
     * {@code 1.9e-4951 / 2e-4951 / 1e-4950 / 9e-4951 / 1e-4949 → "0"}。
     * 界限落在十进制的 1.8 与 1.9 之间，正因为它是二进制格点的一半而不是某个十进制指数。
     * 正好压在界上（{@code |v| = 2^-16446}）时按 {@code HALF_EVEN} 舍到偶数格 0，同样在闸内。
     * 别把它和"舍到非零格点、但按 {@code %.17Lf} 印成 0"混为一谈：后者照收（{@code 2e-4951}）。
     */
    private static final BigDecimal LDBL_UNDERFLOW_LIMIT =
            powerOfTwo(LDBL_SUBNORMAL_STEP_EXPONENT - 1);
    /**
     * {@code |v|} 与 {@link #LDBL_UNDERFLOW_LIMIT} 的精确比较要展开近五千位大数，先用首位十进制
     * 指数收口：{@code ≤ -4952}（即 {@code < 1e-4951 < 1.8226e-4951}）必然已经舍到 0，
     * {@code ≥ -4950} 必然还在网格上，只有 {@code -4951} 这一带需要真比一次。
     * 之所以必须留着这个口子：{@code new BigDecimal("1e-999999999")} 只是记 scale，很轻，
     * 但一次 {@code compareTo} 会把它展开成十亿位。
     */
    private static final int LDBL_UNDERFLOW_DEC_EXPONENT = -4951;

    private RedisDoubleFormat() {
    }

    /**
     * ZSCORE / ZINCRBY / {@code *RANGE ... WITHSCORES} 这一族的形状（对齐 Redis 的 d2string）。
     */
    public static String format(double value) {
        String special = special(value);
        if (special != null) {
            return special;
        }
        if (value == 0.0) {
            // 实测 ZADD z -0.0 m 之后 ZSCORE 回 "-0"；BigDecimal 没有负零，只能在这里分叉。
            return Double.doubleToRawLongBits(value) < 0 ? "-0" : "0";
        }
        BigDecimal d = new BigDecimal(value, SEVENTEEN);
        int exp = d.precision() - d.scale() - 1;
        if (exp < -4 || exp >= PRECISION) {
            String digits = trimTrailingZeros(d.unscaledValue().abs().toString());
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            return (d.signum() < 0 ? "-" : "") + mantissa
                    + "e" + (exp < 0 ? "-" : "+") + twoDigits(Math.abs(exp));
        }
        return fixed(d);
    }

    /** {@link #format(double)} 的 UTF-8 形状，供直接写 bulk 的调用点用。 */
    public static byte[] formatBytes(double value) {
        return format(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * INCRBYFLOAT / HINCRBYFLOAT 一族的"算 + 印"，一次做完，因为这两步在 Redis 里本来就分不开：
     * 它把键里那串文本交给 {@code strtold} 得到一个 long double，加上增量的 long double，
     * 再用 {@code addReplyHumanLongDouble} 把结果打回去 —— 存进去的就是回复的那串
     * （250 实测 14 个输入，回复列与 GET/HGET 列逐例相同）。先在 double 里加完再印，
     * {@code 0.1 + 0.2} 就成了 {@code 0.30000000000000004}，而参考实现回 {@code 0.3}。
     * <p>
     * 打印形状：定点、小数点后最多 17 位、尾零与小数点削光，所以
     * {@code 1e21 → 1000000000000000000000}、{@code 1e-7 → 0.0000001}、
     * {@code 1e-300 → 0}（17 位装不下了）、{@code -0.0 → 0}（分数那一族是 {@code -0}）。
     *
     * @param base  被加数的文本（键里现存的那串，缺失时传 {@code "0"}）
     * @param delta 增量的文本（客户端原样给进来的那串）
     * @throws NumberFormatException 任一文本不是合法浮点，或按 {@code strtold} 的口径溢出 /
     *                               下溢（Redis 在解析阶段就回 {@code value is not a valid float}），
     *                               或文本是 {@code nan}
     * @throws ArithmeticException   结果不是有限值（{@code inf} 参与运算，或相加越过 long double
     *                               的上界）—— Redis 回 {@code increment would produce NaN or Infinity}
     */
    public static String plainSum(String base, String delta) {
        BigDecimal sum = snap(longDouble(base).add(longDouble(delta)));
        if (sum.abs().compareTo(LDBL_MAX) > 0) {
            throw new ArithmeticException("increment would produce NaN or Infinity");
        }
        return printLongDouble(sum);
    }

    /**
     * {@code HINCRBYFLOAT} 一族的"算 + 印"：与 {@link #plainSum} 唯一的区别是<b>不给非有限结果
     * 设闸</b>。参考实现里这两条命令的算术是同一段，检查结果却是 {@code incrbyfloatCommand} 独有
     * 的（250 实测，同一台机器同一个 4.0.9）：
     * <ul>
     *   <li>{@code INCRBYFLOAT k -inf}（原值 1）→ {@code increment would produce NaN or Infinity}
     *       且 {@code GET k} 仍是 "1"；</li>
     *   <li>{@code HINCRBYFLOAT h f -inf}（原值 1）→ {@code "-inf"}，而且这串就写进了字段
     *       （{@code HGET} 回 {@code "-inf"}），下一次 {@code +1} 还是 {@code "-inf"}。</li>
     * </ul>
     * 有限值相加越过 {@code LDBL_MAX} 在 hash 这一族同样是无穷（实测
     * {@code 1e4932 + 1e4932 → "inf"}、{@code -1e4932 + -1e4932 → "-inf"}）。
     * 两个无穷符号相反时 x87 给的是带符号的 indefinite，实测两种顺序都印成 {@code "-nan"}；
     * 那串写回字段后原值就不再加得回来了（实测 {@code HINCRBYFLOAT h f 1} 在 {@code "-nan"}
     * 上回 {@code hash value is not a float}）—— 本方法只负责产出它，读回由调用方的原值校验拒掉。
     *
     * @throws NumberFormatException 任一文本是 {@code nan}，或按 {@code strtold} 的口径
     *                               溢出 / 下溢（两族的这一条是同一把尺）
     */
    public static String plainSumAllowingNonFinite(String base, String delta) {
        boolean baseInf = isInfinityText(base);
        boolean deltaInf = isInfinityText(delta);
        // 有限的那一侧照旧走 strtold 的口径校验（nan、越界都要拒）；无穷的那一侧不需要。
        if (!baseInf) {
            longDouble(base);
        }
        if (!deltaInf) {
            longDouble(delta);
        }
        if (baseInf && deltaInf) {
            boolean negated = isNegativeInfinityText(base) != isNegativeInfinityText(delta);
            return negated ? "-nan" : (isNegativeInfinityText(base) ? "-inf" : "inf");
        }
        if (baseInf) {
            return isNegativeInfinityText(base) ? "-inf" : "inf";
        }
        if (deltaInf) {
            return isNegativeInfinityText(delta) ? "-inf" : "inf";
        }
        BigDecimal sum = snap(longDouble(base).add(longDouble(delta)));
        if (sum.abs().compareTo(LDBL_MAX) > 0) {
            return sum.signum() < 0 ? "-inf" : "inf";
        }
        return printLongDouble(sum);
    }

    /**
     * {@code strtod} / {@code strtold} 认的无穷写法只有两种词干：{@code inf} 与 {@code infinity}，
     * 大小写随意、可带一个符号。250 实测两族都收 {@code INF} / {@code iNf} / {@code +inf} /
     * {@code -INF} / {@code infinity} / {@code Infinity} / {@code +Infinity} / {@code -Infinity}，
     * 而 {@code infi} 与 {@code Infinityx} 一律 {@code value is not a valid float} —— 少一个字母
     * 不算这个词，多一个字母就算散装字符。
     *
     * @return 0 不是无穷写法；1 与 -1 是带符号的无穷。前后有空白的一律 0（{@link #parse} 与
     *         {@code longDouble} 各自会因文法校验把它拒掉，不会当成"没匹配上无穷所以是数字"放行）
     */
    private static int infSign(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        boolean negative = false;
        int i = 0;
        char head = text.charAt(0);
        if (head == '+' || head == '-') {
            negative = head == '-';
            i = 1;
        }
        String stem = text.substring(i);
        if (!stem.equalsIgnoreCase("inf") && !stem.equalsIgnoreCase("infinity")) {
            return 0;
        }
        return negative ? -1 : 1;
    }

    /** 文本是不是 {@code inf} 家族（{@code +inf} / {@code infinity} 这些写法 {@code strtold} 都认）。 */
    public static boolean isInfinityText(String text) {
        return infSign(text) != 0;
    }

    /** 文本是不是带负号的无穷。 */
    public static boolean isNegativeInfinityText(String text) {
        return infSign(text) < 0;
    }

    /**
     * 按 {@code strtold} 的口径把一个十进制文本换成它在 80 位 long double 里真正的那个数
     * （精确值，不是近似：long double 的每个二进制数都能写成有限十进制）。
     */
    private static BigDecimal longDouble(String text) {
        // 这里不许 trim()：参考实现两族都不吃前后空白（250 实测 INCRBYFLOAT k " 1" 与
        // "1\t" 全是 value is not a valid float，battery29），剥掉空白就等于收一批
        // Redis 会当场拒的输入。BigDecimal 与 hexLongDouble 自己的文法都够严，不用再加检查。
        String t = text == null ? "" : text;
        if (t.startsWith(".")) {
            t = "0" + t;            // strtold 认 ".5"，BigDecimal 的文法要整数位
        }
        if (t.endsWith(".")) {
            t = t + "0";            // 同理认 "5."
        }
        if (infSign(t) != 0) {
            // 无穷不在解析阶段死，它一路走到结果检查：实测 INCRBYFLOAT k inf 回的是
            // increment would produce NaN or Infinity，而同一位置的 nan 死在解析（下面那一支）。
            throw new ArithmeticException("increment would produce NaN or Infinity");
        }
        // nan 死在解析：Redis 的 getLongDoubleFromObject 用 isnan() 当场拒（实测
        // INCRBYFLOAT k nan → value is not a valid float），inf 却能一路走到结果检查。
        if ("nan".equalsIgnoreCase(t) || "-nan".equalsIgnoreCase(t) || "+nan".equalsIgnoreCase(t)) {
            throw new NumberFormatException("not a valid float: " + text);
        }
        BigDecimal raw = decimalOrHex(t, text);
        if (raw.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // 先用"首位十进制指数"判范围，再决定要不要展开成 BigDecimal 大数：
        // new BigDecimal("1e-999999999") 本身很轻（只记 unscaled 与 scale），但一次 compareTo
        // 就会把它展开成十亿位。precision/scale 是 O(1) 的，够用来把两侧都挡在前面。
        int decExponent = raw.precision() - raw.scale() - 1;
        BigDecimal mag = raw.abs();
        if (decExponent > LDBL_MAX_DEC_EXPONENT || (decExponent == LDBL_MAX_DEC_EXPONENT
                && mag.compareTo(LDBL_MAX) > 0)) {
            throw new NumberFormatException("not a valid float: " + text);
        }
        // 下溢在这一侧是错误而不是 0：strtold 舍到 0 时置 ERANGE，string2ld 的 errno 那一支把它
        // 接住了。键里存的原值与客户端给的增量走同一个口子，所以两侧同一把尺（实测
        // HSET w f 1e-4951 之后 HINCRBYFLOAT w f 1 → hash value is not a float）。
        if (decExponent < LDBL_UNDERFLOW_DEC_EXPONENT
                || (decExponent == LDBL_UNDERFLOW_DEC_EXPONENT
                        && mag.compareTo(LDBL_UNDERFLOW_LIMIT) <= 0)) {
            throw new NumberFormatException("not a valid float: " + text);
        }
        return snap(raw);
    }

    /** 十进制优先；BigDecimal 不认的形状只剩十六进制浮点（{@code strtold} 的 C99 分支）。 */
    private static BigDecimal decimalOrHex(String t, String original) {
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            // 十进制不中就只有 C99 的十六进制浮点这一条路；BigDecimal 比 strtold 严（不认
            // 十六进制），不会从这里漏进 Redis 其实拒掉的写法。
            return hexLongDouble(t, original);
        }
    }

    /**
     * 十六进制浮点的<b>精确</b>值 —— 不能退回 {@code Double.parseDouble} 兜底，那一跤摔在两头：
     * 250 实测 {@code INCRBYFLOAT k 0x1p+5000} 真值交的是一串 1506 位整数（就是 {@code 2^5000}），
     * 而 {@code 0x1p-1080} 在 80 位里是个普普通通的正规数（实测收、印成 {@code "0"}）；用 double
     * 的口径会把可表示范围压成 64 位，越上的该收的收了错、越下的该拒的拒不掉
     * （{@code 0x1p-16446} 实测是 {@code value is not a valid float}）。
     * <p>
     * C99 的十六进制浮点<b>可以不带指数</b>：实测 {@code 0x10} 两族都收（16 与 1.5），
     * 而 Java 的写法强制要 {@code p}，所以这里自己拆。
     * <p>
     * 值 = {@code M × 2^(E-4f)}，M 是去掉小数点的整截十六进制，f 是小数点后的十六进制位数。
     * 先用 {@code floorLog2 = bitLength(M)-1 + (E-4f)} 做 O(1) 的两侧收口再展开大数：
     * {@code 0x1p+1000000000} 不能变成一次 {@code 5^10^9} 的幂。
     */
    private static BigDecimal hexLongDouble(String t, String original) {
        NumberFormatException bad = new NumberFormatException("not a valid float: " + original);
        int sign = 0;
        if (sign < t.length() && (t.charAt(sign) == '+' || t.charAt(sign) == '-')) {
            sign++;
        }
        boolean negative = sign == 1 && t.charAt(0) == '-';
        // 第二个符号字符就不是数字了（"+-0x1p3" 在 strtod 那里一个字符都不consumed）。
        if (sign > 1 || !t.regionMatches(sign, "0x", 0, 2) && !t.regionMatches(sign, "0X", 0, 2)) {
            throw bad;
        }
        int cursor = sign + 2;
        int dot = -1;
        int end = cursor;
        while (end < t.length()) {
            char c = t.charAt(end);
            if (hexDigit(c) >= 0) {
                end++;
            } else if (c == '.' && dot < 0) {
                dot = end;
                end++;
            } else {
                break;
            }
        }
        String mantissa = t.substring(cursor, dot < 0 ? end : dot)
                + (dot < 0 ? "" : t.substring(dot + 1, end));
        int fractionDigits = dot < 0 ? 0 : end - dot - 1;
        int exponent = 0;
        if (end < t.length() && (t.charAt(end) == 'p' || t.charAt(end) == 'P')) {
            try {
                exponent = Integer.parseInt(t.substring(end + 1));
            } catch (RuntimeException e) {
                throw bad;               // 0x1p、0x1p+、0x1p3x 都落在这一支
            }
            end = t.length();            // parseInt 已经吃掉余下的全部字符
        }
        if (end != t.length() || mantissa.isEmpty()) {
            throw bad;                   // 尾随散装字符，以及 0x / 0xp1 这种没有有效数字的写法
        }
        BigInteger m = new BigInteger(mantissa, 16);
        if (m.signum() == 0) {
            return BigDecimal.ZERO;      // 0x0p0 实测收（本来就是 0，谈不上舍入）
        }
        int shift = exponent - 4 * fractionDigits;
        long log2 = (long) m.bitLength() - 1 + shift;
        if (log2 > LDBL_MAX_EXPONENT) {
            throw bad;                   // 越过 2^16383 的上沿：strtold 交无穷，Redis 当场拒
        }
        // 下溢的判据和十进制那一支同源：非零值被舍到 0（含正好压在半个最小格点上的那次 tie，
        // 走偶数舍到 0）。十六进制的值是二进制的精确有理数，所以这里直接用指数判，不必展开。
        long halfGrid = (long) LDBL_SUBNORMAL_STEP_EXPONENT - 1;
        if (log2 < halfGrid || (log2 == halfGrid && m.bitLength() == 1)) {
            throw bad;
        }
        BigDecimal value = new BigDecimal(m).multiply(powerOfTwo(shift));
        return negative ? value.negate() : value;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * 舍到 long double 的网格上：有效位 64 位，即 {@code v} 必须是 {@code 2^(e-63)} 的整数倍。
     * subnormal 那一侧格子不再随量级变细，步长钉在 {@code 2^-16445}。舍到 0（半个步长以下）
     * 只在<b>相加</b>这一步出现，解析那一步已经先拒了（见 {@link #LDBL_UNDERFLOW_LIMIT}）；
     * 实测 {@code 0 + 1e-4950} 被接受且印成 {@code "0"} —— 那是网格上的非零值，只是
     * {@code %.17Lf} 装不下它的 4951 位小数。
     */
    private static BigDecimal snap(BigDecimal v) {
        if (v.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal mag = v.abs();
        int grid = Math.max(floorLog2(mag) - (LDBL_MANTISSA_BITS - 1), LDBL_SUBNORMAL_STEP_EXPONENT);
        BigDecimal step = powerOfTwo(grid);
        BigDecimal snapped = mag.divide(step, 0, RoundingMode.HALF_EVEN).multiply(step);
        return v.signum() < 0 ? snapped.negate() : snapped;
    }

    /** {@code floor(log2(mag))}，{@code mag > 0}。先用位长估，再用精确比较校正那可能的 ±1。 */
    private static int floorLog2(BigDecimal mag) {
        BigInteger n = mag.unscaledValue().abs();
        long tens = -((long) mag.scale());
        int e = n.bitLength() - 1 + (int) Math.floor(tens * 3.321928094887362d);
        while (powerOfTwo(e).compareTo(mag) > 0) {
            e--;
        }
        while (powerOfTwo(e + 1).compareTo(mag) <= 0) {
            e++;
        }
        return e;
    }

    private static BigDecimal powerOfTwo(int k) {
        // BigDecimal.pow / BigInteger.pow 都拒绝负指数，而 long double 的格点
        // 2^(floorLog2|v|-63) 对任何 |v| < 2^63 都是负指数——也就是说裸 pow 会把
        // 几乎所有输入都抛成 ArithmeticException（实测：整类初始化即失败，72 例全红）。
        // 2^-n = 5^n / 10^n 是有限小数，用 scale 直接表出，不做除法也不丢位。
        return k >= 0 ? new BigDecimal(BigInteger.valueOf(2).pow(k))
                : new BigDecimal(BigInteger.valueOf(5).pow(-k), -k);
    }

    /** {@code %.17Lf} 那一支：定点、小数点后最多 17 位、尾零与小数点削光。 */
    private static String printLongDouble(BigDecimal snapped) {
        if (snapped.signum() == 0) {
            return "0";
        }
        BigDecimal d = snapped;
        if (d.scale() > MAX_PLAIN_FRACTION_DIGITS) {
            d = d.setScale(MAX_PLAIN_FRACTION_DIGITS, RoundingMode.HALF_EVEN);
        }
        // 1e-300 在 17 位小数里塌成 0 —— 实测参考实现回的也是 "0"
        return d.signum() == 0 ? "0" : fixed(d);
    }

    /** NaN 与无穷：两族共用同一套写法（Redis 回的是 inf / -inf，不是 "+inf"）。 */
    private static String special(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        return null;
    }

    private static String fixed(BigDecimal d) {
        String s = d.toPlainString();
        if (s.indexOf('.') < 0) {
            return s;
        }
        int len = s.length();
        while (s.charAt(len - 1) == '0') {
            len--;
        }
        if (s.charAt(len - 1) == '.') {
            len--;
        }
        return s.substring(0, len);
    }

    private static String trimTrailingZeros(String s) {
        int len = s.length();
        while (len > 1 && s.charAt(len - 1) == '0') {
            len--;
        }
        return s.substring(0, len);
    }

    /** glibc 的指数写法：至少两位，三位照写（{@code e-08} / {@code e+308}）。 */
    private static String twoDigits(int absExp) {
        return absExp < 10 ? "0" + absExp : Integer.toString(absExp);
    }

    /**
     * 上面两族输出的反向。取值交给 {@code Double.parseDouble}，但<b>能不能取</b>先按
     * {@code strtod} 的文法自己判一遍（见 {@link #isCStyleFloatText}）：Java 那一侧比 C 宽，
     * {@code 1d}、{@code 1_0}、前后空白它都收，而参考实现三样全拒。反过来
     * {@code Double.parseDouble} 不认 {@code inf} / {@code -inf} —— Redis 认（实测
     * {@code ZADD z inf m} 之后 {@code ZSCORE z m} 回 {@code inf}），于是 {@code COPY}、
     * 持久化回读、{@code HINCRBYFLOAT} 读旧值这三条内部路径都会在 {@code inf} 上抛
     * {@code NumberFormatException}，把一个自己写进去的值当成坏输入。
     *
     * @throws NumberFormatException 不是合法浮点文本（含 {@code nan} 家族：Redis 在解析阶段就拒），
     *                               或写法溢出 / 下溢 double（{@code 1e4000}、{@code 1e-400}
     *                               都超出 {@code strtod} 能交回一个非零数的范围）
     */
    public static double parse(String text) {
        int sign = infSign(text);
        if (sign != 0) {
            return sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        if (!isCStyleFloatText(text)) {
            throw new NumberFormatException("not a valid float: " + text);
        }
        double v = Double.parseDouble(withImplicitHexExponent(text));
        // 写成十进制却溢出 double：strtod 报 errno=ERANGE，Redis 当场拒（实测
        // ZUNIONSTORE ... WEIGHTS 1e4000 → weight value is not a float）。Java 的
        // parseDouble 不报错，它安静地给你 +Infinity —— 不拦就等于凭空接受一个 inf 分数。
        // 反向的下溢是同一支 ERANGE，250 实测（battery24）量清了它也在闸内：
        // ZADD zs 1e-324 / 1e-325 / 1e-400 / 1e-999999 / -1e-325 → value is not a valid float。
        // 而"舍到非零的次正规数"照收：1e-308 → 9.9999999999999991e-309、
        // 1e-320 → 9.9998886718268301e-321、4.9e-324（最小次正规）→ 原样收 —— 所以判据
        // 是"非零文本被舍成 0"，不是"结果次正规"。parseDouble 与 strtod 都是
        // round-to-nearest-even，两者对同一个十进制数给出同一个二进制数，故可直接问 v。
        if (Double.isInfinite(v) || (v == 0.0 && !isZeroText(text))) {
            throw new NumberFormatException("not a valid float: " + text);
        }
        return v;
    }

    /**
     * 这串文本从头到尾是不是一个 C 的浮点数 —— 只判文法，不判值。
     * <p>
     * 存在的理由：{@code parseDouble} 认 Java 的字面量文法，比 {@code strtod} 宽出三类
     * （250 实测 battery28/29，参考实现三类全拒）：类型后缀 {@code 1d} {@code 1D} {@code 1F}
     * {@code 1.5d} {@code 0x1p3f}、Java 7 起的下划线分隔 {@code 1_0}、以及前后空白
     * （空格与制表符都一样拒，虽然 {@code strtod} 自己会跳过前导空白）。逐类打补丁不如把
     * C99 的那条文法写一遍：十进制是"可选符号 + 整数位与小数位至少有一位 + 可选指数"，
     * 十六进制是"可选符号 + {@code 0x} + 十六进制位至少有一位 + 可选的 {@code p} 指数"，
     * 且整个串必须刚好被吃完（指数可省，{@code 0x10} 实测收）。
     */
    private static boolean isCStyleFloatText(String s) {
        int n = s == null ? 0 : s.length();
        if (n == 0) {
            return false;
        }
        // 前后空白不需要单独一条闸：下面这台文法机里没有"空白"这个字符，" 1" 会倒在
        // "整数位与小数位至少一位"，"1 " 会倒在"整个串必须刚好吃完"。实测
        // （JDK 25 与 redis-server 4.0.9 并排量）parseDouble 对 " 1" 与 "1\t" 都交出 1.0，
        // 所以拒它的是这台文法机，不是 Java。
        int i = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            i = 1;
        }
        if (i >= n) {
            return false;
        }
        int digits;
        if (s.charAt(i) == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            i += 2;
            digits = 0;
            while (i < n && hexDigit(s.charAt(i)) >= 0) {
                i++;
                digits++;
            }
            if (i < n && s.charAt(i) == '.') {
                i++;
                while (i < n && hexDigit(s.charAt(i)) >= 0) {
                    i++;
                    digits++;
                }
            }
            if (digits == 0) {
                return false;               // 0x、0x.p1 这种没有有效数字的写法
            }
            if (i == n) {
                return true;                // 隐式 p0
            }
            if (s.charAt(i) != 'p' && s.charAt(i) != 'P') {
                return false;               // 0x1.8f、0x18z 落在这里
            }
            return isExponent(s, ++i, n);
        }
        int integerDigits = 0;
        while (i < n && isDecimalDigit(s.charAt(i))) {
            i++;
            integerDigits++;
        }
        int fractionDigits = 0;
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && isDecimalDigit(s.charAt(i))) {
                i++;
                fractionDigits++;
            }
        }
        if (integerDigits + fractionDigits == 0) {
            return false;                   // "."、"+ .5"、"1.2.3"
        }
        if (i == n) {
            return true;
        }
        if (s.charAt(i) != 'e' && s.charAt(i) != 'E') {
            return false;                   // "1d"、"1_0"、"1 0"、"Infinity" 的非数字写法
        }
        return isExponent(s, ++i, n);
    }

    /** {@code e} / {@code p} 之后那一截：可选符号 + 至少一位十进制数字，且必须是串的结尾。 */
    private static boolean isExponent(String s, int i, int n) {
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < n && isDecimalDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        return digits > 0 && i == n;
    }

    private static boolean isDecimalDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * C99 的十六进制浮点<b>可以不带指数</b>（{@code 0x10} 就是 16、{@code 0x1.8} 就是 1.5，
     * 250 实测两族都收），而 {@code Double.parseDouble} 的写法强制要 {@code p}。
     * 给缺指数的十六进制补一个 {@code p0}，其余文本原样交回去（十进制不能这么动）。
     * 符号要跳过再比前缀，否则 {@code -0x10} 会被当成十进制交给 parseDouble。
     */
    private static String withImplicitHexExponent(String text) {
        int i = (text.charAt(0) == '+' || text.charAt(0) == '-') ? 1 : 0;
        if (!text.regionMatches(i, "0x", 0, 2) && !text.regionMatches(i, "0X", 0, 2)) {
            return text;
        }
        return text.indexOf('p') < 0 && text.indexOf('P') < 0 ? text + "p0" : text;
    }

    /**
     * 文本自己是不是 0 —— 只看有效数字那一截，指数与符号都不改变这件事。
     * <p>
     * 存在的理由：{@code Double.parseDouble} 对 {@code 1e-400} 安静地交出 0，而 {@code strtod}
     * 会置 {@code ERANGE}（实测两族都当场拒）。要分清"本来就是 0"与"非零被舍到 0"，只能回到
     * 文本上量一次，不能问解析结果。
     */
    private static boolean isZeroText(String text) {
        String t = text;
        int i = 0;
        while (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        // 十六进制浮点的指数标记是 p，而 e 在它眼里是**数字**（0xep3 就是 14×8）；
        // 十进制的标记才是 e。认错标记会把非零的有效数字整截丢掉。
        boolean hex = t.regionMatches(i, "0x", 0, 2) || t.regionMatches(i, "0X", 0, 2);
        int stop = t.length();
        for (int j = hex ? i + 2 : i; j < t.length(); j++) {
            char c = t.charAt(j);
            if (c == 'e' || c == 'E' || c == 'p' || c == 'P') {
                if (hex && (c == 'e' || c == 'E')) {
                    continue;
                }
                stop = j;
                break;
            }
        }
        for (int j = i; j < stop; j++) {
            char c = t.charAt(j);
            if (c >= '1' && c <= '9') {
                return false;
            }
            if (hex && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只问一句：这串文本能不能按 {@code strtold} 的口径进 80 位 long double。
     * <p>
     * INCRBYFLOAT 一族的两条文案要分开用：增量写歪了是 {@code value is not a valid float}，
     * 而键里<b>现存</b>的那串不是数字，参考实现回的是 {@code hash value is not a float}
     * （250 实测 {@code HSET h f abc} + {@code HINCRBYFLOAT h f 1} 与增量写歪那支不同）。
     * 一次 {@link #plainSum} 分不出是谁失败，所以调用点要先单独量一遍原值。
     *
     * @throws NumberFormatException 不是合法浮点文本，或按 long double 的口径溢出 / 下溢
     * @throws ArithmeticException   文本是 {@code inf} 家族（结果检查那一支）
     */
    public static void requirePlain(String text) {
        longDouble(text);
    }
}
