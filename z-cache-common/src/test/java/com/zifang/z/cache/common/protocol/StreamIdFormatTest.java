package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StreamIdFormat} 的判据来自上游 {@code t_stream.c}（redis 5.0.14）的
 * {@code streamGenericParseIDOrReply}（:1174）与它所依赖的 {@code string2ull}（:1147）。
 *
 * <p><b>这一族的依据不是实测对拍</b>：z-cache 钉住的参考实例是 redis-server 4.0.9，而 Stream
 * 是 5.0 才有的类型，4.0.9 对每一条 XADD 都回 {@code -ERR unknown command 'XADD'}
 * （250 实测 battery49 整批）。所以这里每一行的右边都是源码那一行<b>说</b>的答案，
 * 并且每一行都写明了行号；只能靠"我以为 C 会这么解析"来定的写法一律不收进这张表。
 */
class StreamIdFormatTest {

    /** 上游 :1205-1206 的原文；任何一处的拒答文案都必须与它逐字相同。 */
    @Test
    void errorTextIsUpstreamVerbatim() {
        assertEquals("Invalid stream ID specified as stream command argument", StreamIdFormat.INVALID_ID);
    }

    /**
     * {@code <ms>-<seq>} 的接受集。第二列是 {@code missing_seq}（没有 {@code -} 时补的那一位，
     * 上游 :1199），实际只有 0 与 UINT64_MAX 两种取法：XRANGE / XPENDING 的终点用 UINT64_MAX
     * （:1357、:2035），其余位置用 0（:1356、:2033）。表里的 -1 就是 UINT64_MAX 的位模式。
     */
    @ParameterizedTest(name = "parse(\"{0}\", missing_seq={1}, strict={2}) = {3}")
    @CsvSource({
            //  文本,                   missing_seq, strict, 期望的规范化写法
            "'0-0',                    0,           false,  '0-0'",
            "'1-1',                    0,           false,  '1-1'",
            "'1-1',                    -1,          false,  '1-1'",
            "5,                        0,           false,  '5-0'",
            "5,                        -1,          false,  '5-18446744073709551615'",
            "'18446744073709551615-18446744073709551615', 0, false, '18446744073709551615-18446744073709551615'",
            // uint64 而不是 int64：2^63 这一档 string2ll 收不下，退到 strtoull 才收（:1156）
            "'9223372036854775808-0',  0,           false,  '9223372036854775808-0'",
            // strtoull 比 string2ll 宽出的三类：前导零、正号、前导空白
            "'05-1',                   0,           false,  '5-1'",
            "'+1-1',                   0,           false,  '1-1'",
            "' 1',                     0,           false,  '1-0'",
            "'1-+1',                   0,           false,  '1-1'",
            "'1- 1',                   0,           false,  '1-1'",
            "'01-01',                  0,           false,  '1-1'",
            // 只按第一个 '-' 切一刀（:1194 的 strchr），后面那段整体交给 string2ull
            "'1- -1',                  0,           false,  '1-18446744073709551615'",
    })
    void acceptsWhatUpstreamAccepts(String text, long missingSeq, boolean strict, String canonical) {
        long[] parsed = StreamIdFormat.parse(text, missingSeq, strict);
        assertEquals(canonical, parsed == null ? null : StreamIdFormat.format(parsed[0], parsed[1]),
                "parse(\"" + text + "\", " + missingSeq + ", " + strict + ")");
    }

    /**
     * 拒绝集，每一行都有源码那一行作依据：
     * <ul>
     *   <li>{@code -1} / {@code 1--1}：string2ll 成功后发现是负数，:1150 直接 return 0，
     *       <b>不会</b>退到 strtoull —— 这与 {@code 1- -1} 被接受是一对，差别只在前面那个空白
     *       让 string2ll 先失败，于是走了 strtoull 的回绕（{@code (unsigned long long)-1}）。</li>
     *   <li>{@code 1 } / {@code x} / {@code 1-1-1} / {@code 1-}：strtoull 要求把整串吃光
     *       （:1157 的 {@code *endptr == '\0'}），残留一个字符或一个字符都没有就否。</li>
     *   <li>{@code 18446744073709551616}：超过 2^64，strtoull 置 ERANGE（:1157）。</li>
     *   <li>{@code -} / {@code +}：只有在 strict 位上才否（:1179-1180）。</li>
     * </ul>
     */
    @ParameterizedTest(name = "parse(\"{0}\", strict={1}) 必须非法")
    @MethodSource("rejectedIds")
    void rejectsWhatUpstreamRejects(String text, boolean strict) {
        assertNull(StreamIdFormat.parse(text, 0L, strict), "parse(\"" + text + "\", strict=" + strict + ")");
        assertNull(StreamIdFormat.parse(text, StreamIdFormat.MAX_U64, strict),
                "换成 missing_seq=UINT64_MAX 也不能被放过: " + text);
    }

    static Stream<Arguments> rejectedIds() {
        return Stream.of(
                Arguments.of("-1", false),
                Arguments.of("1--1", false),
                Arguments.of("1 ", false),
                Arguments.of("x", false),
                Arguments.of("1-1-1", false),
                Arguments.of("1-", false),
                Arguments.of("", false),
                Arguments.of("18446744073709551616", false),
                Arguments.of("18446744073709551616-0", false),
                Arguments.of("0-x", false),
                Arguments.of("-", true),
                Arguments.of("+", true),
                Arguments.of(null, false));
    }

    /**
     * {@code -} 与 {@code +} 在 XRANGE 这类位置就是最小值与最大值（:1183-1190），
     * 但在 XADD / XDEL / XACK / XREAD / XGROUP CREATE 这类位置是非法 ID（:1179-1180）。
     * 同一份文法两种答案，靠 strict 一位分开，不能合并成一句。
     */
    @Test
    void minusAndPlusAreRangeBoundsButNotStrictPositions() {
        long[] minus = StreamIdFormat.parse("-", 0L, false);
        assertEquals("0-0", StreamIdFormat.format(minus[0], minus[1]), "上游 :1183-1186");
        long[] plus = StreamIdFormat.parse("+", 0L, false);
        assertEquals("18446744073709551615-18446744073709551615",
                StreamIdFormat.format(plus[0], plus[1]), "上游 :1187-1190 的 UINT64_MAX");
        assertNull(StreamIdFormat.parse("-", 0L, true));
        assertNull(StreamIdFormat.parse("+", 0L, true));
        // 特判只吃单独的 "-" / "+" 这一种形状："1-" 走的是普通分支，照样非法
        assertNull(StreamIdFormat.parse("-x", 0L, false));
    }

    /**
     * {@code char buf[128]}（:1175-1176）是<b>长度</b>闸，和数值闸（:1157 的 ERANGE）是两道：
     * 127 个零再加一个 1，数值上是 1，照样要收；把它撑到 128 个字符，即便数值仍然合法，
     * 上游也在 memcpy 之前就 goto invalid 了。写成 128 位全 1 那种数字就分不出这两道闸。
     */
    @Test
    void longerThanTheStackBufferIsInvalid() {
        StringBuilder padded = new StringBuilder();
        for (int i = 0; i < 126; i++) padded.append('0');
        padded.append('1');
        assertEquals(127, padded.length());
        long[] parsed = StreamIdFormat.parse(padded.toString(), 0L, false);
        assertEquals("1-0", StreamIdFormat.format(parsed[0], parsed[1]), "127 个字符仍然要收");

        assertEquals(128, padded.append('1').length());
        assertNull(StreamIdFormat.parse(padded.toString(), 0L, false), "128 个字符超过 buf[128]，与数值无关");

        assertNull(StreamIdFormat.parse("92233720368547758081-0", 0L, false),
                "数值闸是另一道：20 位超出 uint64，长度却完全合法");
    }

    /**
     * 两段都是 uint64，必须按无符号比：{@code 18446744073709551615-0} 是最大的 ID，
     * 而有符号的 {@code long} 把它存成了 -1 —— 用 {@code Long.compare} 会把它判成最小。
     * 这一条不是纸面担忧：XADD 之后 {@code Stream.generateId()} 要判"当前时间是否比上一条新"，
     * 符号一比就把刚写进去的最大 ID 当成历史，于是自动 ID 回退到流里已有序号之前。
     */
    @Test
    void orderingIsUnsignedOnBothSegments() {
        long top = StreamIdFormat.MAX_U64;
        assertEquals(-1L, top, "Java 的 long 只能按位存 UINT64_MAX");
        assertTrue(Long.compare(top, 1L) < 0, "有符号比较给出的正是错的那个方向");
        assertTrue(StreamIdFormat.compare(top, 0L, 1L, 0L) > 0);
        assertTrue(StreamIdFormat.compare(1L, 0L, top, 0L) < 0);
        assertTrue(StreamIdFormat.compare(top, top, top, 1L) > 0, "ms 相同要比 seq，也是无符号");
        assertEquals(0, StreamIdFormat.compare(top, top, top, top));
        assertTrue(StreamIdFormat.compare(0L, 0L, top, top) < 0,
                "XRANGE \"-\" \"+\" 的两端必须是序的两端，否则范围查恒空");
    }

    /** {@code format} 与 {@code parse} 必须成对：规范化写法再读回来要落在同一对数上。 */
    @Test
    void formatIsStableUnderReparse() {
        String[] texts = {"0-0", "1-1", "5", "05-1", "+1-1", "9223372036854775808-0",
                "18446744073709551615-18446744073709551615"};
        for (String text : texts) {
            long[] parsed = StreamIdFormat.parse(text, 0L, false);
            String canonical = StreamIdFormat.format(parsed[0], parsed[1]);
            long[] again = StreamIdFormat.parse(canonical, 0L, false);
            assertEquals(canonical, StreamIdFormat.format(again[0], again[1]), "二次解析要落在同一点上: " + text);
        }
    }

    /**
     * {@code streamIncrID}（:77-89）：序号进位到时间戳，两段都到顶则回绕成 {@code 0-0}。
     * 回绕这一支在 XREAD 上是看不见的（:1591 那道"流非空且 maxid > gt"的闸先把它挡住了），
     * 但 XREADGROUP 读历史没有那道闸 —— 所以这一位必须按上游真的会回绕来算，不能钳在顶。
     */
    @Test
    void successorCarriesAndWrapsLikeStreamIncrID() {
        assertArrayEquals(new long[]{1L, 3L}, StreamIdFormat.successor(1L, 2L));
        long top = StreamIdFormat.MAX_U64;
        assertArrayEquals(new long[]{2L, 0L}, StreamIdFormat.successor(1L, top), "seq 到顶进位到 ms");
        assertArrayEquals(new long[]{0L, 0L}, StreamIdFormat.successor(top, top), "MAX-MAX 之后是 0-0，不是钳顶");
        assertArrayEquals(new long[]{top, 0L}, StreamIdFormat.successor(top - 1, top),
                "进位落在无符号最大值上，比较仍要按无符号走");
        assertTrue(StreamIdFormat.compare(top, top, 0L, 0L) > 0, "回绕点两侧的顺序不能反");
    }

    /** 客户端敲进来的字节什么形状都有：null 进去只能是"非法"，不能是异常。 */
    @Test
    void nullIsRejectedNotThrown() {
        assertNull(StreamIdFormat.parse(null, 0L, false));
        assertNull(StreamIdFormat.parse(null, StreamIdFormat.MAX_U64, true));
    }
}
