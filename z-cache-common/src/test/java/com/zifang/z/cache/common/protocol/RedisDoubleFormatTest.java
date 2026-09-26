package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisDoubleFormat} 的期望值全部来自 250 上的一次性 redis-server 4.0.9 参考实例
 * （量具：{@code ~/zcache-250t/measure_stored.sh}，同一份输入既看回复也看存进去的字节，
 * 两列逐例相同；文法与界限那一串见 {@code ~/.cache/zcache_gauges/battery23..30.txt}，
 * 两侧跑同一个 {@code zreplay.py}、比同一份 {@code zdiff.py}）。
 * 这里不写"我认为 Redis 会怎么打"，只写量到的原文。
 *
 * @author zifang
 * @since 1.3.6
 */
class RedisDoubleFormatTest {

    /** 分数一族：ZSCORE / ZINCRBY / {@code *RANGE … WITHSCORES}。 */
    @ParameterizedTest(name = "ZSCORE({0}) = {1}")
    @CsvSource({
            "1,                  1",
            "1.0,                1",
            "1.5,                1.5",
            "100000,             100000",
            "0.0001,             0.0001",
            "0.1,                0.10000000000000001",
            "3.14159265358979,   3.14159265358979",
            "1e16,               10000000000000000",
            "1e17,               1e+17",
            "1e21,               1e+21",
            "1e-5,               1.0000000000000001e-05",
            "1e-7,               9.9999999999999995e-08",
            "1e-300,             1e-300",
            "1e-320,             9.9998886718268301e-321",
            "1.7976931348623157e308, 1.7976931348623157e+308",
            "-0.0,               -0",
            "0.0,                0",
            "inf,                inf",
            "-inf,               -inf",
            "+inf,               inf"
    })
    void scoreFamilyMatchesTheReference(String input, String expected) {
        assertEquals(expected, RedisDoubleFormat.format(parse(input)), "分数一族: " + input);
        assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                RedisDoubleFormat.formatBytes(parse(input)), "字节形状要与字符串同一把尺: " + input);
    }

    /**
     * humanReadable 一族：INCRBYFLOAT / HINCRBYFLOAT，永不用科学计数、小数点后最多 17 位。
     * <p>
     * 量法与参考实例上一致：把一个十进制文本塞进键里，再用 {@code INCRBYFLOAT k 0} 逼它把自己
     * 重新格式化一遍（{@code plainSum(text, "0")}）—— 250 上 {@code ~/zcache-250t/probe_plain.sh}
     * 就是这么量的，所以这里的输入是"键里那串文本"，不是 Java 的 double。这一点很要紧：
     * 走 double 的话 {@code 0.33333333333333333333} 只会剩下 16 个 3，而参考实例回 17 个。
     */
    @ParameterizedTest(name = "INCRBYFLOAT {0} + 0 = {1}")
    @CsvSource({
            "1,                  1",
            "1.0,                1",
            "1.5,                1.5",
            "100000,             100000",
            "0.0001,             0.0001",
            "0.1,                0.1",
            "3.14159265358979,   3.14159265358979",
            "1e16,               10000000000000000",
            "1e17,               100000000000000000",
            "1e21,               1000000000000000000000",
            "1e-5,               0.00001",
            "1e-7,               0.0000001",
            "1e-300,             0",
            "1e-320,             0",
            "0.123456789012345678, 0.12345678901234568",
            "1.0000000000000002, 1.0000000000000002",
            "-0.0,               0",
            "0.0,                0",
            "0.33333333333333333333, 0.33333333333333333",
            "3.141592653589793238462643383279, 3.14159265358979324",
            "0.99999999999999999999, 1",
            "123456789012345678, 123456789012345678",
            "12345678901234567890123, 12345678901234567889920",
            "1e-17,              0.00000000000000001",
            "2.5e-17,            0.00000000000000002",
            "0.00000000000000000001, 0"
    })
    void humanReadableFamilyMatchesTheReference(String input, String expected) {
        assertEquals(expected, RedisDoubleFormat.plainSum(input, "0"), "humanReadable 一族: " + input);
    }

    /**
     * 累加：参考实例每一步都把上一步打出的那串重新读回来算（存的就是回复），所以这里同样
     * 一步步喂文本。{@code 0.1 + 0.2} 回 {@code 0.3} 而不是 {@code 0.30000000000000004} ——
     * Redis 的加法在 80 位 long double 里做，噪声落在第 19 位，打印只有 17 位小数，看不见。
     * 分数那一族（double 算）同一个输入回的正是 {@code 0.30000000000000004}，两族并排钉住。
     */
    @Test
    void accumulationFollowsTheReferencesLongDoubleArithmetic() {
        assertEquals("0.3", RedisDoubleFormat.plainSum("0.1", "0.2"));
        assertEquals("0.8", RedisDoubleFormat.plainSum("0.1", "0.7"));
        assertEquals("1.1", RedisDoubleFormat.plainSum("0.1", "1.0"));
        assertEquals("1", RedisDoubleFormat.plainSum("1", "1e-18"));
        assertEquals("1000000000000000000000", RedisDoubleFormat.plainSum("1e21", "0.5"));

        // 实测序列：0.1 累加十次，"0.2" "0.3" … "1"，一步都不漂
        String acc = "0";
        String[] steps = new String[10];
        for (int i = 0; i < steps.length; i++) {
            acc = RedisDoubleFormat.plainSum(acc, "0.1");
            steps[i] = acc;
        }
        assertArrayEquals(new String[]{"0.1", "0.2", "0.3", "0.4", "0.5",
                "0.6", "0.7", "0.8", "0.9", "1"}, steps);

        // 同一输入在分数一族里就是另一个答案（实测 ZINCRBY z 0.2 m，m 已是 0.1）
        assertEquals("0.30000000000000004", RedisDoubleFormat.format(0.1 + 0.2));

        // 1e308 + 1e308：double 会溢出成 Infinity，long double 还有一千多位可写。
        assertEquals("1999999999999999999933717593116912913211201996948311344155940959898"
                + "43469737676123744200253843777078640893494450108026446304269499187921167"
                + "19484162886039283753591820003920638155732621920901421333587830679157787"
                + "78291210871261225367298032372604341731785068897632475826017115146362848"
                + "49020905456510092687857156096", RedisDoubleFormat.plainSum("1e308", "1e308"));

        // 十六进制浮点是 strtold 认的（实测 INCRBYFLOAT k 0x1p3 → 9）
        assertEquals("9", RedisDoubleFormat.plainSum("1", "0x1p3"));
    }

    /**
     * 连续两次"重新格式化自己"必须落在同一个点上：键里存的、回复出去的是同一串，
     * 下一次 {@code INCRBYFLOAT k 0} 不该把它磨成第三种形状。
     */
    @ParameterizedTest(name = "stable {0}")
    @CsvSource({
            "0.33333333333333333333", "0.1", "1e21", "1e-300", "2.5e-17",
            "12345678901234567890123", "0.99999999999999999999", "1e308"
    })
    void reformattingAnAlreadyFormattedStringIsStable(String input) {
        String once = RedisDoubleFormat.plainSum(input, "0");
        assertEquals(once, RedisDoubleFormat.plainSum(once, "0"), "第二次格式化要原地不动: " + input);
    }

    /** 非有限的输入在哪一步死掉，是实测出来的两件事：nan 死在解析，inf 死在结果检查。 */
    @Test
    void nonFiniteInputsFailWhereTheReferenceFails() {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("1", "nan"));
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("abc", "1"));
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("1", "1e99999999"));
        assertThrows(ArithmeticException.class, () -> RedisDoubleFormat.plainSum("1", "inf"));
        assertThrows(ArithmeticException.class, () -> RedisDoubleFormat.plainSum("inf", "1"));

        // 分数一族：写成十进制却溢出 double 的，参考实现在解析阶段就拒（250 实测
        // ZADD z 1e4000 m 与 ZADD z 1e309 m 都是 "value is not a valid float"，
        // ZUNIONSTORE ... WEIGHTS 1e4000 是同一句的族变体）。Java 的 parseDouble 不报错，
        // 它安静地给你 +Infinity —— 不拦就是凭空接受一个 inf 分数。
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse("1e4000"));
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse("1e309"));
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse("-1e4000"));
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse("nan"));
        // 而字面 inf 是活的：实测 ZADD z inf m 之后 ZSCORE 回 "inf"
        assertEquals(Double.POSITIVE_INFINITY, RedisDoubleFormat.parse("inf"), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, RedisDoubleFormat.parse("-inf"), 0.0);

        // NaN 只可能从内部算术出来（命令层已经在解析阶段拒了），形状照参考实例的写法
        assertEquals("nan", RedisDoubleFormat.format(Double.NaN));
        assertEquals("inf", RedisDoubleFormat.format(Double.POSITIVE_INFINITY));
        assertEquals("-inf", RedisDoubleFormat.format(Double.NEGATIVE_INFINITY));
    }

    /**
     * 下溢的界限是<b>量级</b>，不是某个十进制指数：{@code strtold} 把非零文本舍到半个最小格点
     * （{@code 2^-16446 ≈ 1.8226e-4951}）以下时交出 0 并置 {@code ERANGE}，Redis 的
     * {@code string2ld} 见 {@code errno != 0} 就回 {@code value is not a valid float}。
     * 于是界限落在十进制的 1.8 与 1.9 之间 —— 这一族曾经记反成"下溢不是错误"，把
     * {@code 1e-4951} 收了下来（真值实测：拒）。
     * 而"舍到非零格点、但 {@code %.17Lf} 印成 0"的那一侧是照收的，两半必须都有用例，
     * 否则一把只往一边偏的尺会全绿。
     * <p>
     * 250 实测（redis-server 4.0.9，battery23 三十九行）逐行钉在这里；INCRBYFLOAT 与
     * HINCRBYFLOAT 两族用的是同一把尺，所以每一行两个方法各跑一遍。
     */
    @ParameterizedTest(name = "underflow {0} -> {1}")
    @CsvSource({
            // 舍到 0 ⇒ ERANGE ⇒ 拒
            "1e-4951,   ERR", "1.8e-4951,  ERR", "1e-4952,  ERR", "2e-4952,  ERR",
            "1e-9999,   ERR", "-1e-4951,   ERR",
            // 舍到最小格点（非零），只是印不出来 ⇒ 收，回复 "0"
            "1.9e-4951, 0", "2e-4951,    0", "9e-4951,    0", "1e-4950,    0",
            "1e-4949,    0", "-1e-4950,   0",
    })
    void underflowIsRejectedWhereStrtoldRoundsToZero(String input, String expected) {
        if ("ERR".equals(expected)) {
            assertThrows(NumberFormatException.class,
                    () -> RedisDoubleFormat.plainSum("0", input), "增量侧要拒: " + input);
            assertThrows(NumberFormatException.class,
                    () -> RedisDoubleFormat.plainSum(input, "0"), "原值侧同一把尺: " + input);
            assertThrows(NumberFormatException.class,
                    () -> RedisDoubleFormat.plainSumAllowingNonFinite("0", input),
                    "hash 一族不给结果设闸，但解析侧同一把尺: " + input);
        } else {
            assertEquals(expected, RedisDoubleFormat.plainSum("0", input), input);
            assertEquals(expected, RedisDoubleFormat.plainSumAllowingNonFinite("0", input), input);
            // 落在网格上的非零值原样写回键里，下一次还得被同一个人读回来
            assertEquals(expected, RedisDoubleFormat.plainSum(RedisDoubleFormat.plainSum("0", input), "0"),
                    "自己写的串要读得回来: " + input);
        }
    }

    /**
     * 写得出去就得读得回来：{@code COPY}、持久化回读、{@code HINCRBYFLOAT} 读旧值都是拿本类
     * 打出的那串再解析的，以前用 {@code Double.parseDouble} 读不回来 {@code inf}（自己写、自己拒）。
     * 分数一族还必须是无损的——它就是 double 的 17 位有效数字。
     */
    @ParameterizedTest(name = "round-trip {0}")
    @CsvSource({
            "1", "1.5", "0.1", "3.14159265358979", "1e16", "1e17", "1e21",
            "1e-5", "1e-7", "1e-300", "1e-320", "1.7976931348623157e308",
            "-0.0", "0.0", "inf", "-inf"
    })
    void whatWeWriteWeCanReadBack(String input) {
        double v = parse(input);
        assertEquals(v, RedisDoubleFormat.parse(RedisDoubleFormat.format(v)), 0.0,
                "分数一族必须无损回读: " + input);
    }

    /**
     * 分数一族的下溢界限，同样是"非零文本被舍成 0"这一条（250 实测 battery24/25 钉死）：
     * {@code 1e-324}（半个最小次正规 {@code 2^-1075} 之下）拒，而 {@code 1e-320}、
     * {@code 4.9e-324} 这些"次正规但非零"照收 —— 也就是说拒的不是次正规，是零。
     * 边界两侧的 {@code 2.47e-324}（拒）与 {@code 2.48e-324}（收）只差在十进制第三位上，
     * 只往一边偏的尺过不了这一组。
     */
    @ParameterizedTest(name = "parse({0}) = {1}")
    @CsvSource({
            "1e-308,       1e-308",
            "1e-320,       1e-320",
            "4.9e-324,     4.9e-324",
            "2.48e-324,    4.9e-324",
            "2.5e-324,     4.9e-324",
            "0x1p-1074,    4.9e-324",
            "0.0e-400,     0",
            "0e-999999,    0",
            "0x10,         16",
            "0x1.8,        1.5",
            "0X1P+4,       16",
            "0x1p3,        8",
            "0xabcdefp0,   11259375",
    })
    void scoreFamilyAcceptsEveryTextThatRoundsToANonZeroDouble(String text, double expected) {
        assertEquals(expected, RedisDoubleFormat.parse(text), 0.0, text);
    }

    @ParameterizedTest(name = "parse({0}) 必须拒")
    @CsvSource({
            "1e-324", "1e-325", "1e-400", "1e-999999", "-1e-325", "-1e-400",
            "2.4e-324", "2.47e-324", "0x1p-1080", "0x1p-1075",
            "0x1p+1024", "0x1p+1000000000", "0x1p-1000000000",
    })
    void scoreFamilyRejectsNonZeroTextThatRoundsToZero(String text) {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse(text), text);
    }

    /** 负号在被舍到 0 的那一侧也要保住：实测 {@code ZADD z -0e-400 m} → {@code ZSCORE} 回 "-0"。 */
    @Test
    void negativeZeroSurvivesAnAbsurdExponent() {
        assertEquals("-0", RedisDoubleFormat.format(RedisDoubleFormat.parse("-0e-400")));
        assertEquals("-0", RedisDoubleFormat.format(RedisDoubleFormat.parse("-0")));
        assertEquals("0", RedisDoubleFormat.format(RedisDoubleFormat.parse("0e-999999")));
    }

    /**
     * 十六进制浮点是 {@code strtold}/{@code strtod} 的 C99 分支，实测（battery26/27）三件事：
     * 指数可以整个不带（{@code 0x10} 就是 16），范围是 80 位的而不是 double 的
     * （{@code 0x1p+5000} 真值是一串 1506 位整数），以及越界的写法一律当场拒。
     * 那几串大数的期望值用 {@code BigInteger.shiftLeft} 现算 —— 一条与实现无关的口子，
     * 不是把被测代码再走一遍。
     */
    @ParameterizedTest(name = "hex {0} -> {1}")
    @CsvSource({
            "0x10,           16",
            "0x1.8,          1.5",
            "0x.8p0,         0.5",
            "+0x1p3,         8",
            "-0x1p3,         -8",
            "0x0p0,          0",
            "0x00000000000000000000000000000010p0, 16",
            "0x1.8p3,        12",
            "0xabcdefp0,     11259375",
            "0x1.8p-4,       0.09375",
            "0x1p-1080,      0",
            "0x1p-16445,     0",
    })
    void hexFloatsFollowStrtoldWithinLongDoubleRange(String text, String expected) {
        assertEquals(expected, RedisDoubleFormat.plainSum("0", text), text);
        // 原值那一侧同一把尺：HSET 存进去的串，下一次增量要能读回来
        RedisDoubleFormat.requirePlain(text);
    }

    @Test
    void hexFloatsExpandExactlyWhereDoubleCannotReach() {
        assertEquals(plain(twosPower(5000)), RedisDoubleFormat.plainSum("0", "0x1p+5000"));
        assertEquals(plain(twosPower(16383)), RedisDoubleFormat.plainSum("0", "0x1p+16383"));
        assertEquals(plain(twosPower(16382).multiply(java.math.BigInteger.valueOf(3))),
                RedisDoubleFormat.plainSum("0", "0x1.8p+16383"));
        // (2 - 2^-52)×2^16382 = 2^16383 - 2^16330
        assertEquals(plain(twosPower(16383).subtract(twosPower(16330))),
                RedisDoubleFormat.plainSum("0", "0x1.fffffffffffffp+16382"));
        // 正好抵在 2^16384 上：strtold 交无穷，Redis 拒（实测）
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", "0x1p+16384"));
        // 正好压在半个最小格点上：tie 走偶数舍到 0，同样拒（实测）
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", "0x1p-16446"));
    }

    @ParameterizedTest(name = "坏写法 {0} 两族都拒")
    @CsvSource({
            "0x1p", "0x", "0xp1", "+-0x1p3", "0x1.2.3p0", "0x1p3junk", "0x1p+",
            "0x1p+1000000000", "0x1p-1000000000", "00x10", "0x1.8p0p0",
    })
    void malformedHexFailsBothFamilies(String text) {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", text), text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse(text), text);
    }

    /**
     * 前后空白不算容忍、算拒。250 实测四个位置（分数族的分数位、增量族的增量位与原值位）
     * 全是 {@code value is not a valid float}，空格与制表符同命（battery28 第 20—33 行、
     * battery29 第 2—18 行）。{@code strtod} 自己会跳过前导空白而 Redis 不让它跳；
     * {@code Double.parseDouble} 两头都容忍（本机 JDK 25 实测 {@code " 1"}、{@code "1\t"} 都交 1.0）。
     * 同一条断言在 {@link #theSameSpellingsWithoutWhitespaceAreLegal()} 里有反面：把这四句
     * 改成"什么都拒"的尺会满场绿，只有两侧都钉住才算有牙。
     */
    @ParameterizedTest(name = "带空白的 {0} 两族四个位置都拒")
    @ValueSource(strings = {" 1", "1 ", " 1 ", "\t1", "1\t", " +1", "+ 1", "1 0", " ", " inf ", "-infinity "})
    void surroundingWhitespaceIsNotTrimmedAnywhere(String text) {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse(text), "分数族: " + text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", text), "增量位: " + text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum(text, "0"), "原值位: " + text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.requirePlain(text), "原值校验: " + text);
        assertFalse(RedisDoubleFormat.isInfinityText(text), "无穷判定也不许剥空白: " + text);
    }

    /** 上面那一条的阳性对照：同一批写法去掉空白就是合法浮点。 */
    @Test
    void theSameSpellingsWithoutWhitespaceAreLegal() {
        assertEquals(1.0, RedisDoubleFormat.parse("1"), 0.0);
        assertEquals(1.0, RedisDoubleFormat.parse("+1"), 0.0);
        assertEquals("1", RedisDoubleFormat.plainSum("0", "1"));
        assertEquals("1", RedisDoubleFormat.plainSum("1", "0"));
        assertTrue(RedisDoubleFormat.isInfinityText("inf"), "对照的另一半：不带空白的无穷得认出来");
    }

    /**
     * Java 的字面量文法比 {@code strtod} 宽：类型后缀（{@code 1d} {@code 1D} {@code 1F}
     * {@code 1.5d}，十六进制尾巴上那个 {@code 0x1p3f} 同理）和 Java 7 起的下划线分隔
     * {@code 1_0}。250 实测参考实现两族全拒（battery28 第 2—5 行、battery29 第 20—31 行）。
     */
    @ParameterizedTest(name = "Java 独有写法 {0} 两族都拒")
    @ValueSource(strings = {"1d", "1D", "1f", "1F", "1.5d", "0x1p3f", "1_0"})
    void javaOnlyNumericSpellingsFailBothFamilies(String text) {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse(text), "分数族: " + text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", text), "增量族: " + text);
    }

    /**
     * {@code strtod} 认的无穷只有 {@code inf} 与 {@code infinity} 两个词干，大小写随意、可带
     * 一个符号；Java 的 {@code parseDouble} 恰好相反——{@code Infinity} 收、{@code inf} 不收。
     * 两边的词表都不照抄，实测样本（battery28 第 8—17 行、battery29 第 46—60 行）钉在这里。
     * 写出去的永远是参考实例的那一种写法：{@code inf}。
     */
    @ParameterizedTest(name = "分数族 parse({0}) 打回 {1}")
    @CsvSource({
            "Infinity,   inf", "+Infinity,  inf", "INFINITY,   inf", "infinity,   inf",
            "inf,        inf", "INF,        inf", "iNf,        inf", "+inf,       inf",
            "-Infinity, -inf", "-inf,      -inf", "-INF,      -inf",
    })
    void scoreFamilyAcceptsEveryInfinitySpelling(String text, String expected) {
        assertEquals(expected, RedisDoubleFormat.format(RedisDoubleFormat.parse(text)), text);
    }

    /** 长得像无穷与 nan 的写法：多一个字、少一个字母都不算（实测两族都拒）。 */
    @ParameterizedTest(name = "{0} 两族都拒")
    @ValueSource(strings = {"infi", "Infinityx", "infinit", "nan", "NAN", "-nan", "+nan", "nan(1)"})
    void lookalikeInfinityAndNaNSpellingsFailBothFamilies(String text) {
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.parse(text), "分数族: " + text);
        assertThrows(NumberFormatException.class, () -> RedisDoubleFormat.plainSum("0", text), "增量族: " + text);
    }

    /**
     * 增量族里无穷的死亡位置与 {@code nan} 不同：它穿过解析、死在结果检查，
     * 文案是 {@code increment would produce NaN or Infinity}（实测 battery28 第 30—32 行、
     * battery30 第 14—17 行）；而 {@code HINCRBYFLOAT} 一族根本没有这道闸，inf 会真写进字段，
     * 之后再加一次还是 {@code inf}（battery30 第 18—22 行）。
     */
    @ParameterizedTest(name = "HINCRBYFLOAT 1 {0} = {1}")
    @CsvSource({
            "inf,       inf", "INF,       inf", "infinity,    inf", "+infinity,   inf",
            "Infinity,  inf", "-inf,     -inf", "-infinity,  -inf",
    })
    void longDoubleCarriesInfinityToTheResultCheck(String text, String expected) {
        String delta = text;
        assertThrows(ArithmeticException.class, () -> RedisDoubleFormat.plainSum("1", delta), delta);
        assertEquals(expected, RedisDoubleFormat.plainSumAllowingNonFinite("1", delta), delta);
        assertEquals(expected, RedisDoubleFormat.plainSumAllowingNonFinite(expected, "1"),
                "inf 落进字段之后再加 1，参考实例还是原样: " + delta);
    }

    /**
     * 带符号的十六进制与"只有一点"的十进制（battery30 第 32—63 行逐条照抄）。这两个形状
     * 各咬住一处实现细节：{@code 0x} 前缀要比到符号后面去，否则 {@code -0x10} 会被当成十进制
     * 交出去；而 {@code 5.} 与 {@code .5} 在 C 与 Java 两侧都合法。
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "-0x10,    -16", "+0x1.8,   1.5", "-0x.8p0,  -0.5", "5.,       5",
            ".5,       0.5", "-.5,     -0.5",
    })
    void signedHexAndBareDotShapesMatchTheReference(String text, String expected) {
        assertEquals(expected, RedisDoubleFormat.plainSum("0", text), "增量位: " + text);
        RedisDoubleFormat.requirePlain(text);
        assertEquals(Double.parseDouble(expected), RedisDoubleFormat.parse(text), 0.0, "分数位: " + text);
    }

    /** 同一批写法落在键里当原值，下一次加 1 的真值（battery30 第 53—64 行）。 */
    @Test
    void referenceReadsItsOwnDotAndHexShapesBackOutOfTheKey() {
        assertEquals("6", RedisDoubleFormat.plainSum("5.", "1"));
        assertEquals("1.5", RedisDoubleFormat.plainSum(".5", "1"));
        assertEquals("-2", RedisDoubleFormat.plainSum("-0x1.8p1", "1"));
    }

    /** {@code 2^n}，走 {@code BigInteger} 这条与实现无关的口子。 */
    private static java.math.BigInteger twosPower(int n) {
        return java.math.BigInteger.ONE.shiftLeft(n);
    }

    private static String plain(java.math.BigInteger v) {
        return new java.math.BigDecimal(v).toPlainString();
    }

    private static double parse(String literal) {
        if ("inf".equals(literal) || "+inf".equals(literal)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-inf".equals(literal)) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.parseDouble(literal);
    }
}
