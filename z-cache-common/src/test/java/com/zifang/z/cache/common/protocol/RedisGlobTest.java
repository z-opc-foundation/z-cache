package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisGlob} 的判据表 —— 每一行的期望值都是把 {@code util.c:stringmatchlen()}
 * 的那几个臂按着走一遍得出来的，出处见被测类的 javadoc。
 * <p>
 * 形状照 13 系列的老规矩：整张表收在一格里跑完，最后一次性断言，红的时候把不合格的行
 * 全部点名（一支变异只要改错一个臂，往往同时打翻好几行，逐行 {@code assertEquals} 只会
 * 让我们看见第一行）。
 * <p>
 * 另一条规矩也照办：每个"不许匹配"的旁边都钉一行同形状的"要匹配"。只看否定式的行，
 * {@code return false} 那个实现也能全绿 —— 阳性对照不是补充，是否定式行成立的前提。
 */
class RedisGlobTest {

    /** pattern，text，nocase，期望；四样一起写，红的时候才看得懂是哪一格。 */
    private static final class Row {
        final String pattern, text;
        final boolean nocase, expected;

        Row(String pattern, String text, boolean nocase, boolean expected) {
            this.pattern = pattern;
            this.text = text;
            this.nocase = nocase;
            this.expected = expected;
        }

        String label() {
            return "pattern=" + quote(pattern) + " text=" + quote(text) + " nocase=" + nocase
                    + " 期望 " + expected;
        }

        private static String quote(String s) {
            return "[" + s.replace("\\", "\\\\") + "]";
        }
    }

    private static List<Row> table() {
        List<Row> r = new ArrayList<>();
        // ---- 没有元字符的那一档：整串逐字比 ----
        r.add(new Row("auto-aof-rewrite-percentage", "auto-aof-rewrite-percentage", false, true));
        r.add(new Row("auto-aof-rewrite-perc", "auto-aof-rewrite-percentage", false, false));   // 前缀不是匹配
        r.add(new Row("auto-aof-rewrite-percentage", "auto-aof-rewrite-perc", false, false));
        r.add(new Row("", "", false, true));
        r.add(new Row("", "x", false, false));
        r.add(new Row("x", "", false, false));

        // ---- * ----
        r.add(new Row("*", "anything", false, true));
        r.add(new Row("auto-aof-*", "auto-aof-rewrite-min-size", false, true));
        r.add(new Row("auto-aof-*", "maxmemory", false, false));
        r.add(new Row("auto-aof-rewrite-*", "auto-aof-rewrite-percentage", false, true));
        r.add(new Row("a*b", "axxb", false, true));
        r.add(new Row("a*b", "ab", false, true));
        r.add(new Row("a*b", "ba", false, false));
        r.add(new Row("**", "abc", false, true));                                                // 连写的 * 折叠
        r.add(new Row("a**b", "axxb", false, true));
        r.add(new Row("abc*", "abc", false, true));                                              // 串先耗尽、尾巴纯 *
        r.add(new Row("abc*", "ab", false, false));
        r.add(new Row("*a", "aa", false, true));
        r.add(new Row("*a", "ba", false, true));
        r.add(new Row("*a", "ab", false, false));

        // ---- 全 * 图案 × 空串键名：版本差那三格，钉的是"照 5.0.14"这一档 ----
        // 两档读数各自在案：提取自 5.0.14 util.c 的 C 量具（~/.cache/zcache_gauges
        // /sml_extract.inc，md5=a8dc715f60c13ce1979faf211d510a7a）这三格回 0，
        // 而那台活的 4.0.9 在 battery_ref.tsv（md5=fc4e54245739d6b57573927f40f484d4）
        // 的第 53/101/149 行同三格回 True —— 差的就是 5.0.14 循环头多挡的那半个
        // stringLen。13t 拿变异（把那个条件摘掉＝B7 臂）问过这两张表：全仓只这三格会红，
        // 也就是补上这六行之前，<em>matcher 级没有任何一行盯得住它</em>。
        r.add(new Row("*", "", false, false));                                                   // 空串是"要 false"，
        r.add(new Row("**", "", false, false));                                                  // 旁边三行 `a` 是同形状的
        r.add(new Row("***", "", false, false));                                                  // "要 true"对照，不是补充
        r.add(new Row("*", "a", false, true));
        r.add(new Row("**", "a", false, true));
        r.add(new Row("***", "a", false, true));

        // ---- ? ----
        r.add(new Row("?", "a", false, true));
        r.add(new Row("?", "", false, false));
        r.add(new Row("??", "ab", false, true));
        r.add(new Row("??", "a", false, false));
        r.add(new Row("a?c", "abc", false, true));
        r.add(new Row("a?c", "ac", false, false));
        r.add(new Row("a?c", "a[c", false, true));                                               // ? 吃进去的字符不当元字符看

        // ---- 大小写：同形状只翻 nocase 那一个开关 ----
        r.add(new Row("AUTO-AOF-*", "auto-aof-rewrite-percentage", true, true));
        r.add(new Row("AUTO-AOF-*", "auto-aof-rewrite-percentage", false, false));
        r.add(new Row("A?C", "abc", true, true));
        r.add(new Row("A?C", "abc", false, false));
        r.add(new Row("a_b", "A_B", true, true));
        r.add(new Row("a_b", "A_B", false, false));

        // ---- 字符集 ----
        r.add(new Row("[abc]", "b", false, true));
        r.add(new Row("[abc]", "d", false, false));
        r.add(new Row("[^abc]", "d", false, true));
        r.add(new Row("[^abc]", "a", false, false));
        r.add(new Row("[a-c]", "b", false, true));
        r.add(new Row("[a-c]", "d", false, false));
        r.add(new Row("[c-a]", "b", false, true));                                               // 两端反着写也认
        r.add(new Row("[c-a]", "d", false, false));
        r.add(new Row("x[a-z]y", "xby", false, true));
        r.add(new Row("x[a-z]y", "xay", false, true));
        r.add(new Row("x[a-z]y", "xYy", false, false));
        r.add(new Row("[A-Z]", "q", true, true));                                                // nocase 时区间两端一起折
        r.add(new Row("[A-Z]", "q", false, false));
        r.add(new Row("[\\]]", "]", false, true));                                               // 集内转义的 ]
        r.add(new Row("[\\]]", "a", false, false));
        r.add(new Row("[\\\\]", "\\", false, true));                                              // 集内的转义反斜杠本身
        r.add(new Row("[\\\\]", "a", false, false));
        r.add(new Row("[a-]", "]", false, true));                                                // 尾巴那个 - 是区间，不是字面量
        r.add(new Row("[a-]", "-", false, false));
        r.add(new Row("[ab", "a", false, true));                                                  // 未闭合：C 的回退走法
        r.add(new Row("[ab", "b", false, true));
        r.add(new Row("[ab", "c", false, false));
        r.add(new Row("[]", "x", false, false));

        // ---- 集外的 \ ----
        r.add(new Row("a\\*b", "a*b", false, true));
        r.add(new Row("a\\*b", "axb", false, false));
        r.add(new Row("\\?", "?", false, true));
        r.add(new Row("\\?", "x", false, false));
        r.add(new Row("a\\", "a\\", false, true));                                                // 收尾一根孤零零的 \：按字面量比
        r.add(new Row("a\\", "ab", false, false));
        return r;
    }

    @Test
    void theTableMatchesTheCMachine() {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        for (Row row : table()) {
            boolean actual = RedisGlob.matches(row.pattern, row.text, row.nocase);
            seen.add(row.label() + " ⇒ " + actual);
            if (actual != row.expected) {
                wrong.add(row.label() + "，实际 " + actual);
            }
        }
        assertTrue(wrong.isEmpty(),
                "glob 判据表不合格 " + wrong.size() + "/" + seen.size() + " 行：\n  "
                        + String.join("\n  ", wrong) + "\n[RED_ROWS=" + wrong.size() + "/" + seen.size() + "]");
    }
}
