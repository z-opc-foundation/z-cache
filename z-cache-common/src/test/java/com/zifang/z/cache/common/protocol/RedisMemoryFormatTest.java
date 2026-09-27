package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisMemoryFormat} 的判据 = 上游 {@code util.c:197-246} 那四十行的判据
 * （redis-5.0.14，本机权威副本 {@code ~/.cache/zcache_gauges/full5x/redis-5.0.14/src/util.c}）。
 * <p>
 * 每一行右边都是照那段源码逐行走出来的，不是"我以为带单位的数字该怎么解析"：单位表在
 * {@code :211-228}，负号与数字那一圈在 {@code :206-209}，{@code strtoll} 那一步在
 * {@code :235-243}。<b>本轮没有对参考实例复算</b>（250 自 09-27 06:40 起 ssh 不通），
 * 恢复后要拿 {@code CONFIG SET auto-aof-rewrite-min-size} 在真 redis-server 上逐行重跑一遍，
 * 尤其这三行最容易凭印象写错：<code>"05mb"</code>（收，5242880）、<code>"kb"</code>（收 0）、
 * <code>"-b"</code>（拒）。溢出那一行是<em>故意</em>与 C 不同，理由写在类注释里。
 * </p>
 */
class RedisMemoryFormatTest {

    /** 上游收下的写法，以及它拒掉的写法里 Java 会收下（或会算错）的那些。第二列为空 = {@code err = 1}。 */
    @ParameterizedTest(name = "memtoll(\"{0}\") = {1}")
    @CsvSource({
            "0,                     0",
            "-0,                    0",
            "'',                    0",
            "b,                     0",
            "kb,                    0",
            "5,                     5",
            "1024,                  1024",
            "1k,                    1000",
            "1kb,                   1024",
            "1Kb,                   1024",
            "1MB,                   1048576",
            "100mb,                 104857600",
            "100m,                  100000000",
            "1g,                    1000000000",
            "1gb,                   1073741824",
            "2b,                    2",
            "05mb,                  5242880",
            "-1,                    -1",
            "-5mb,                  -5242880",
            "9223372036854775807,   9223372036854775807",
            "'+',                  ''",
            "'+1mb',               ''",
            "'-',                  ''",
            "'-b',                 ''",
            "'1.5mb',              ''",
            "'0x10',               ''",
            "'1e2',                ''",
            "' 1mb',               ''",
            "'1mb ',               ''",
            "'1kbb',               ''",
            "'1kb2',               ''",
            "'1mb1',               ''",
            "'Infinity',           ''",
            "99999999999999999999, ''",
            "9223372036854775807kb, ''"
    })
    void matchesTheUpstreamMemoryGrammar(String input, String expected) {
        Long got = RedisMemoryFormat.parse(input);
        assertEquals(expected.isEmpty() ? null : Long.valueOf(expected), got, "memtoll: " + input);
        assertEquals(expected.isEmpty(), !RedisMemoryFormat.isMemoryText(input), "判据两问同答: " + input);
    }

    /**
     * "为什么要自己写"那一面：三处看着能省事的写法各自错在哪儿，每一处都是一行真判据。
     */
    @Test
    void shortcutsWouldMisparse() {
        // endsWith("mb") 那一类剥单位的写法会把前导零判成非法，而 C 侧的 strtoll 收（util.c:235）。
        assertEquals(Long.valueOf(5242880L), RedisMemoryFormat.parse("05mb"),
                "前导零在上游是收的，Java 的 parseLong 也收，但 Redis 那套 string2ll 语法不收");
        // Java 的 parseLong 面对空串只会抛；C 侧 digits=0 交出 0 且 err=0（util.c:239-242）。
        assertEquals(Long.valueOf(0L), RedisMemoryFormat.parse(""), "空串收成 0");
        assertEquals(Long.valueOf(0L), RedisMemoryFormat.parse("kb"), "只有单位、没有数字，同样是 0");
        // 先取绝对值再乘单位的写法会把溢出绕成一个能塞进 long 的数；这里一律拒。
        assertNull(RedisMemoryFormat.parse("9223372036854775808"), "数字段溢出");
        assertNull(RedisMemoryFormat.parse("9223372036854775807kb"), "乘单位之后溢出");
    }
}
