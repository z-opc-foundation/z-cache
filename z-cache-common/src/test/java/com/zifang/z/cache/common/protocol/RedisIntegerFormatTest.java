package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisIntegerFormat} 的判据就是参考实例的判据 —— 每一行的右边都是从
 * redis-server 4.0.9 上抄回来的回复，不是"我以为 C 会怎么解析"。
 * <p>
 * 量的地方：250 上的一次性实例（{@code ~/zcache-250t/zref.sh battery37.txt ... 6391}）。
 * 同一份电池也在本机跑过一遍，两侧逐行对拍；这里挑出的是"字符串 → 数值"那一维，
 * 因为服务端拿到的就是这一维，剩下的形状（{@code :1} / {@code -ERR ...}）在
 * {@code battery37.zref.tr} 与 {@code battery37.zloc.tr} 里留了原文。
 */
class RedisIntegerFormatTest {

    /** 参考实例接受的全部写法，以及它拒掉的写法里 Java 会接受的那些。 */
    @ParameterizedTest(name = "string2ll(\"{0}\") = {1}")
    @CsvSource({
            "0,                            0",
            "5,                            5",
            "-5,                           -5",
            "10,                           10",
            "-10,                          -10",
            "9223372036854775807,          9223372036854775807",
            "-9223372036854775808,         -9223372036854775808",
            "'9223372036854775808',        ''",
            "'-9223372036854775809',       ''",
            "'99999999999999999999',       ''",
            "'+5',                         ''",
            "'-0',                         ''",
            "'05',                         ''",
            "'00',                         ''",
            "'-05',                        ''",
            "'0x10',                       ''",
            "'1e2',                        ''",
            "'5.0',                        ''",
            "'3.14',                       ''",
            "'',                           ''",
            "'-',                          ''",
            "'Infinity',                   ''",
            "'nan',                        ''"
    })
    void matchesTheReferenceParser(String input, String expected) {
        Long got = RedisIntegerFormat.parse(input);
        assertEquals(expected.isEmpty() ? null : Long.valueOf(expected), got, "string2ll: " + input);
        assertEquals(expected.isEmpty(), !RedisIntegerFormat.isIntegerText(input), "判据两问同答: " + input);
    }

    /**
     * 这三行是"为什么要自己写"的全部理由：Java 的 {@code parseLong} 会把它们收下，
     * 而参考实例回 {@code value is not an integer or out of range}（实测见类注释）。
     * 如果哪天有人想把这里换回 {@code Long.parseLong}，这一支会先变红。
     */
    @Test
    void javaAcceptsExactlyWhatTheReferenceRejects() {
        assertEquals(5L, Long.parseLong("+5"));
        assertEquals(0L, Long.parseLong("-0"));
        assertEquals(5L, Long.parseLong("05"));
        assertNull(RedisIntegerFormat.parse("+5"), "正号：参考实例不认");
        assertNull(RedisIntegerFormat.parse("-0"), "负零：参考实例在语法这一档就拒");
        assertNull(RedisIntegerFormat.parse("05"), "前导零：参考实例不认");
    }

    /** {@code EXPIRE k -0} 与 {@code EXPIRE k 0} 两句不同的答案，根源就在语法/范围这两档不在同一处。 */
    @Test
    void zeroIsLegalButNegativeZeroNeverReachesTheRangeCheck() {
        assertEquals(Long.valueOf(0L), RedisIntegerFormat.parse("0"));
        assertNull(RedisIntegerFormat.parse("-0"));
        assertFalse(RedisIntegerFormat.isIntegerText("-0"));
    }

    /** 空白不参与"宽容"：{@code INCRBY k " 5"} 在参考实例里也是报错（实测 battery37 第 7、8 行）。 */
    @ParameterizedTest(name = "\"{0}\" 不是整数文本")
    @MethodSource("whitespaceShapes")
    void surroundingWhitespaceIsNotAnInteger(String input) {
        assertNull(RedisIntegerFormat.parse(input));
        assertNull(RedisIntegerFormat.parseAsInt(input));
    }

    static Stream<Arguments> whitespaceShapes() {
        return Stream.of(
                Arguments.of(" 5"),
                Arguments.of("5 "),
                Arguments.of("5\t"),
                Arguments.of("\n5"),
                Arguments.of("5\r"),
                Arguments.of(" "),
                Arguments.of(""));
    }

    /**
     * {@code SET k v EX 4000000000} 实测两侧不同：参考实例 {@code +OK} 且 {@code TTL} 回
     * {@code :4000000000}，旧实现用 {@code Integer.parseInt} 直接把它判成"不是整数"。
     * 也就是说"值是个合法 long"和"值装得进 int"是两件事，必须分得开。
     */
    @Test
    void aLegalLongThatDoesNotFitAnIntIsTwoDifferentAnswers() {
        String text = "4000000000";
        assertEquals(Long.valueOf(4000000000L), RedisIntegerFormat.parse(text));
        assertNull(RedisIntegerFormat.parseAsInt(text));
        assertThrowsLikeParseLongOverflow(text);

        assertEquals(Integer.valueOf(15), RedisIntegerFormat.parseAsInt("15"));
        assertEquals(Integer.valueOf(-15), RedisIntegerFormat.parseAsInt("-15"));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), RedisIntegerFormat.parseAsInt("2147483647"));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), RedisIntegerFormat.parseAsInt("-2147483648"));
        assertNull(RedisIntegerFormat.parseAsInt("2147483648"));
        assertNull(RedisIntegerFormat.parseAsInt("-2147483649"));
        assertNull(RedisIntegerFormat.parseAsInt("+5"));
        assertNull(RedisIntegerFormat.parseAsInt(null));
    }

    private static void assertThrowsLikeParseLongOverflow(String text) {
        try {
            Integer.parseInt(text);
            throw new AssertionError("前提变了：Integer.parseInt(\"" + text + "\") 竟然收下了");
        } catch (NumberFormatException expected) {
            // 这正是旧实现把它误判成"不是整数"的那条路。
        }
    }

    /** null 进去不能抛 NPE：调用点拿到的是"这一串不能用"，不是异常。 */
    @Test
    void nullAndEmptyAreRejectedNotThrown() {
        assertNull(RedisIntegerFormat.parse(null));
        assertNull(RedisIntegerFormat.parse(""));
        assertFalse(RedisIntegerFormat.isIntegerText(null));
    }

    /** 边界逐位试出来：19 位的三档（MIN / MAX / MAX+1）与 20 位起一律拒。 */
    @Test
    void longRangeIsInclusiveOnBothEnds() {
        assertEquals(Long.valueOf(Long.MAX_VALUE), RedisIntegerFormat.parse(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.valueOf(Long.MIN_VALUE), RedisIntegerFormat.parse(String.valueOf(Long.MIN_VALUE)));
        assertNull(RedisIntegerFormat.parse("9223372036854775808"));
        assertNull(RedisIntegerFormat.parse("-9223372036854775809"));
        // 32 位以上：参考实例在 string2ll 里另有一条长度快通道，这里靠范围检查收到同一个答案。
        assertNull(RedisIntegerFormat.parse("10000000000000000000000000000000000"));
    }
}
