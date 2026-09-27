package com.zifang.z.cache.common.protocol;

/**
 * Redis 的那把 glob —— 与 C 侧 {@code util.c:stringmatchlen()} 逐臂同判据。
 *
 * <p>
 * 为什么要有这么个类：{@code CONFIG GET <pattern>} 收的是 glob，不是"前缀"也不是正则。
 * 手上现成的替代品有两个，两个都会错：{@code String.startsWith} 把 {@code auto-aof-*} 之外的
 * 写法全当字面量；{@code CommandHandler.globToRegex} 把 {@code [} {@code ]} 转义成正则里的
 * 字面方括号，于是 {@code [ab]} 那一档在两边根本不是同一个语言。判据得集中在一条，
 * 而且这条要能单独被变异验牙 —— 挂在命令层的格子做不到这一点。
 * </p>
 *
 * <p>
 * 照抄的判据（{@code util.c} 里那个函数，逐臂）：{@code *} 吃掉任意个字符（连写的多个
 * {@code *} 先折叠，结尾就是 {@code *} 时直接命中）；{@code ?} 吃掉一个字符；
 * {@code [..]} 是字符集，开头一个 {@code ^} 取反，集内 {@code a-z} 是区间（两端反着写也认，
 * 内部先交换），集内 {@code \x} 是转义；集外一根 {@code \} 把下一个字符降级成字面量。
 * 未闭合的 {@code ]} 按 C 的走法就地收尾，整段被当成"已匹配一个字符"处理 —— 这条看着古怪，
 * 但它是 {@code patternLen == 0} 那一支原样带回退的行为，不是我们自己的取舍。
 * </p>
 *
 * <p>
 * 两处必须说清的翻译：
 * <ul>
 *   <li>C 在越界处读到的是字符串末尾那个 {@code '\0'}，Java 会抛
 *       {@code StringIndexOutOfBoundsException}。所以取字符一律走 {@link #at}，
 *       下标出界就交回 {@code '\0'} —— 少了这一步，{@code [ab} 这种未闭合的集会把命令打崩，
 *       而不是回一个不匹配。</li>
 *   <li>{@code nocase} 那两侧用的是 C 的 {@code tolower}：默认区域下它只动 {@code A-Z}，
 *       字节 ≥ 0x80 的一律原样。{@code Character.toLowerCase} 会把 {@code 'Å'} 折成
 *       {@code 'å'}，那是 C 侧不会做的事，所以这里写死一张 ASCII 表（{@link #lower}）。</li>
 * </ul>
 *
 * <p>
 * 期望值出自上面那份源码的逐臂推演，不是对拍读数 —— 250 恢复之后要拿参考实例
 * （{@code CONFIG GET} 与 {@code PATTELMatch} 那一类）复跑一遍这张表。
 * 另外记下：{@code SCAN} 的 {@code MATCH} 现在走的仍是 {@code globToRegex}，两边对
 * {@code [..]} 的取舍不同，且 {@code stringmatch} 在 SCAN 那一侧是区分大小写的（
 * {@code stringmatchlen(..., 0)}），CONFIG GET 这一侧才带 {@code nocase=1}。这一处不一致
 * 是遗留，不是本类的判断。
 * </p>
 */
public final class RedisGlob {

    private RedisGlob() {
    }

    /** 区分大小写的匹配，等价于 C 的 {@code stringmatch(pattern, text, 0)}。 */
    public static boolean matches(String pattern, String text) {
        return matches(pattern, text, false);
    }

    /**
     * 等价于 C 的 {@code stringmatch(pattern, text, nocase)}。
     *
     * @param nocase true 时字符比较一律先折成小写，对应 {@code CONFIG GET} 那一侧的用法
     */
    public static boolean matches(String pattern, String text, boolean nocase) {
        return matchLen(pattern, 0, pattern.length(), text, 0, text.length(), nocase);
    }

    private static boolean matchLen(String pat, int p, int patLen,
                                    String s, int stringIdx, int stringLen, boolean nocase) {
        while (patLen > 0 && stringLen > 0) {
            char pc = at(pat, p);
            if (pc == '*') {
                while (at(pat, p + 1) == '*') {                  // 连写的 * 先折叠
                    p++;
                    patLen--;
                }
                if (patLen == 1) {
                    return true;                                 // 以 * 收尾 ⇒ 已经吃掉剩下全部
                }
                while (stringLen > 0) {
                    if (matchLen(pat, p + 1, patLen - 1, s, stringIdx, stringLen, nocase)) {
                        return true;
                    }
                    stringIdx++;
                    stringLen--;
                }
                return false;
            }
            if (pc == '?') {
                // C 在这里还有一句 "if (stringLen == 0) return 0"：外层 while 已经保证了
                // stringLen > 0，那一句打不到（等价代码，不写）。
                stringIdx++;
                stringLen--;
            } else if (pc == '[') {
                p++;
                patLen--;
                boolean not = at(pat, p) == '^';
                if (not) {
                    p++;
                    patLen--;
                }
                boolean match = false;
                while (true) {
                    char c = at(pat, p);
                    if (c == '\\' && patLen >= 2) {
                        p++;
                        patLen--;
                        if (at(pat, p) == at(s, stringIdx)) {
                            match = true;
                        }
                    } else if (c == ']') {
                        break;
                    } else if (patLen == 0) {
                        p--;                                     // 未闭合：把游标退回 C 的位置
                        patLen++;
                        break;
                    } else if (at(pat, p + 1) == '-' && patLen >= 3) {
                        int start = at(pat, p);
                        int end = at(pat, p + 2);
                        int cur = at(s, stringIdx);
                        if (start > end) {                       // 反着写的区间照样认
                            int t = start;
                            start = end;
                            end = t;
                        }
                        if (nocase) {
                            start = lower(start);
                            end = lower(end);
                            cur = lower(cur);
                        }
                        p += 2;
                        patLen -= 2;
                        if (cur >= start && cur <= end) {
                            match = true;
                        }
                    } else if (nocase) {
                        if (lower(c) == lower(at(s, stringIdx))) {
                            match = true;
                        }
                    } else {
                        if (c == at(s, stringIdx)) {
                            match = true;
                        }
                    }
                    p++;
                    patLen--;
                }
                if (not) {
                    match = !match;
                }
                if (!match) {
                    return false;
                }
                stringIdx++;
                stringLen--;
            } else {
                if (pc == '\\' && patLen >= 2) {                 // 集外的 \：下一字符是字面量
                    p++;
                    patLen--;
                    pc = at(pat, p);
                }
                char sc = at(s, stringIdx);
                boolean same = nocase ? lower(pc) == lower(sc) : pc == sc;
                if (!same) {
                    return false;
                }
                stringIdx++;
                stringLen--;
            }
            p++;
            patLen--;
            if (stringLen == 0) {
                while (at(pat, p) == '*') {                      // 串先耗尽：尾巴上纯 * 也算吃掉
                    p++;
                    patLen--;
                }
                break;
            }
        }
        return patLen == 0 && stringLen == 0;
    }

    /** C 在越界处读到的是结尾的 {@code '\0'}；这里如实带回同一个字符。 */
    private static char at(String s, int idx) {
        return idx >= 0 && idx < s.length() ? s.charAt(idx) : '\0';
    }

    /** C 默认区域下的 {@code tolower}：只动 {@code A-Z}。 */
    private static int lower(int c) {
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }
}
