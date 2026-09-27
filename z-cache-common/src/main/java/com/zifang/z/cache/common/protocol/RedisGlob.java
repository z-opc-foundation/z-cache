package com.zifang.z.cache.common.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Redis 的那把 glob —— 与 C 侧 {@code util.c:stringmatchlen()} 逐臂同判据，<em>按字节</em>走。
 *
 * <p>
 * 为什么要有这么个类：{@code CONFIG GET <pattern>} 收的是 glob，不是"前缀"也不是正则。
 * 手上现成的替代品有两个，两个都会错：{@code String.startsWith} 把 {@code auto-aof-*} 之外的
 * 写法全当字面量；{@code globToRegex} 那一族（把 {@code [} {@code ]} 转义成正则里的字面方括号，
 * {@code [ab]} 那一档在两边根本不是同一个语言）在仓里曾有六份私有副本，如今<em>代码里一处不剩</em>：
 * 键枚举那两处（{@code MemoryStore} 与 {@code CommandHandler} 各一份）13s 删的，成员枚举那四处
 * （{@code HashStore.hscan}、{@code SetStore.sscan}、{@code SortedSetStore.zscan}，以及
 * {@code PubSubManager} 的 {@code getChannels}＋{@code publish}＋那份递归 {@code matchPattern}）
 * 13v 删的。今天 {@code src/main} 里还能 grep 到的命中全在注释里。
 * 判据得集中在一条，而且这条要能单独被变异验牙：结构那一半由
 * {@code RedisGlobDeliveryTest.memberFacesHaveNoPrivateDialectLeft} 逐文件数着（违禁字样 0 命中、
 * 共享那把<em>各接恰好一次</em>、命令层不许把"没给 MATCH"折成一根 {@code *} 哨兵），交付那一半由
 * {@code memberFacesDeliverTheReferenceSets} 真起一台从线上问（五张面的期望集出自 C 生成的表）。
 * 只挂在命令层的格子做不到这一点：那两层各自都量到过"改了语义而格子全绿"。
 * </p>
 *
 * <p>
 * 照抄的判据（{@code util.c} 里那个函数，逐臂）：{@code *} 吃掉任意个字节（连写的多个
 * {@code *} 先折叠，结尾就是 {@code *} 时直接命中）；{@code ?} 吃掉<em>一个字节</em>；
 * {@code [..]} 是字节的集合，开头一个 {@code ^} 取反，集内 {@code a-z} 是区间（两端反着写也认，
 * 内部先交换），集内 {@code \x} 是转义；集外一根 {@code \} 把下一个字节降级成字面量。
 * 未闭合的 {@code ]} 按 C 的走法就地收尾，整段被当成"已匹配一个字节"处理 —— 这条看着古怪，
 * 但它是 {@code patternLen == 0} 那一支原样带回退的行为，不是我们自己的取舍。
 * </p>
 *
 * <p>
 * <b>"按字节"这件事有两处必须说清的翻译，两处都是量出来的，不是推演出来的。</b>
 * <ul>
 *   <li>粒度：C 的 {@code stringmatchlen} 收的是 SDS 的<em>字节</em>长度与字节游标，
 *       所以一个 {@code ?} 吃进去的是 UTF-8 的一个字节，不是一个字符。这里以前按
 *       {@code char}（UTF-16 码元）走，于是 {@code ?} 对 {@code 中} 反着答：上游 0、这里 1。
 *       13t 起改成进出都过一遍 {@link StandardCharsets#UTF_8}，长度与游标一律是字节。
 *       <b>这不是"为了支持中文"，是为了和上游同答</b>：非 ASCII 键名在参照那一台上
 *       本来就是字节序列。</li>
 *   <li>符号：C 里区间臂写的是 {@code int start = pattern[0]}，{@code char} 在 x86_64 Linux
 *       上<em>带符号</em>，于是 0xC3 进 int 是 -61 而不是 195 —— {@code [a-é]} 这一支在
 *       带符号档是"两端反着写、换完界后 [-61, 97]"，在无符号档才是 [97, 195]。
 *       同一份从 5.0.14 源码机械切出的 C 量具编两支（{@code -fsigned-char} /
 *       {@code -funsigned-char}），拿 4428 格专门挑这一族的电池去问一台活的 4.0.9：
 *       带符号那一档<em>逐格同答</em>（0 格不同），无符号那一档 836 格不同
 *       （旧的那 2928 对里 0 格能分辨，所以这一档<em>非得另配电池</em>才量得到）。
 *       这里用 Java 的 {@code byte}（同样带符号）如实带回 C 在那个平台上的读法。
 *       换到 {@code char} 无符号的平台（aarch64 Linux、macOS 的 clang 默认档）
 *       上游自己就会换答案 —— 我们钉的是参照那一台的档位，不是"我认为该是哪档"。</li>
 * </ul>
 *
 * <p>
 * 另外两处翻译一直写着，如今仍然成立：
 * <ul>
 *   <li>C 在越界处读到的是字符串末尾那个 {@code '\0'}，Java 会抛
 *       {@code StringIndexOutOfBoundsException}。所以取字节一律走 {@link #at}，
 *       下标出界就交回 0 —— 少了这一步，{@code [ab} 这种未闭合的集会把命令打崩，
 *       而不是回一个不匹配。</li>
 *   <li>{@code nocase} 那两侧用的是 C 的 {@code tolower}：默认区域下它只动 {@code A-Z}，
 *       字节 ≥ 0x80 的一律原样（传进去的是带符号的负数，glibc 那张表对它也是身份映射）。
 *       {@code Character.toLowerCase} 会把 {@code 'Å'} 折成 {@code 'å'}，那是 C 侧不会做的事，
 *       所以这里写死一张 ASCII 表（{@link #lower}）。</li>
 * </ul>
 *
 * <p>
 * 这张表的期望值最初出自上面那份源码的逐臂推演；13r 拿一台活的参照逐行问过
 * （250 上的 {@code redis-server 4.0.9}，一次性实例）—— 问得到的 59 行逐行同答，
 * 同一轮还把整张表摊进 2928 个 {@code (图案, 键名)} 对里与参照对拍；13t 又加上
 * 上面那 4428 格的字节轴电池。
 * <p>
 * 13r 记下的那处"遗留不一致"由两轮各自闭合：{@code KEYS} 与 {@code SCAN MATCH} 在 13s
 * 接上本类（{@code MemoryStore.keyPatternMatches}，{@code nocase = false}，与上游
 * {@code db.c:550}、{@code db.c:748} 那两句 {@code stringmatchlen(..., 0)} 同档；带
 * {@code nocase=1} 的仍然只有 {@code CONFIG GET} 一家），连上游"图案恰好一根 {@code *}"
 * 那句快路也一起抄了（{@code db.c:545}、{@code :663}）；字节粒度这一半是 13t（卡 #36）。
 * <p>
 * 还剩两笔账，各自有名，不冒充已经做完：
 * ① 空串键名遇上 {@code *}／{@code **}／{@code ***}：4.0.9 的循环头只挡 {@code patternLen}，
 * 折叠后剩一根 {@code *} 就判命中，5.0.14 的循环头（{@code util.c:51}）多挡一个
 * {@code stringLen}、空串直接判不匹配 —— 本类照 5.0.14（上面逐臂抄的就是这一版）。
 * 这一笔 13t 由 C 量具独立复现：同一份提取体（5.0.14）与那台 4.0.9 在旧电池的 2928 格里
 * 只差这三格。而 {@code KEYS *} 那一格由上面那句快路收回，所以线上只有 {@code KEYS **}
 * 这种写法看得见它 —— 13v 把这一格的可见面摊开了：成员枚举那一族（{@code HSCAN}／
 * {@code SSCAN}／{@code ZSCAN} 的 {@code MATCH}）与 {@code PUBSUB CHANNELS} 上游本来就
 * <em>没有</em>那句快路（{@code db.c:748}、{@code pubsub.c:351}），所以 {@code MATCH *}
 * 不交空成员、而"没给 MATCH"要交 —— 这一对方向相反的格子由
 * {@code RedisGlobDeliveryTest} 的 {@code D1a}/{@code D1b}/{@code D1c} 与
 * {@code D2s0}/{@code D2h0}/{@code D2z0} 钉住，期望集直接从 C 生成。13t 又拿变异问过这两支判据：<em>把循环头那半个 {@code stringLen}
 * 摘掉（＝退回 4.0.9 那一档），matcher 级两支判据一支都不红</em> —— 于是这三格本身被
 * 补进了 {@code RedisGlobTest}（连同三行同形状的 {@code true} 对照，两档读数各自在案），
 * 这一档从此在仓里有牙。
 * ② 字节粒度这一半只到"键名是一串合法 UTF-8"为止：库里的键名是先被
 * {@code new String(bytes, UTF_8)} 解成 {@code String} 的（入站那一层
 * {@code RedisServerHandler.java:124} → {@code RespArray.java:105} →
 * {@code RespBulkString.java:57}），不合法的字节序列在那一步就已经换了字节
 * （一个 {@code 0xFF} 变三字节的 U+FFFD），这里再编码也回不去。
 * 这一笔是<em>入站解码的编码选择</em>，不是匹配器的，另开卡跟。
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
     * @param nocase true 时字节比较一律先折成小写，对应 {@code CONFIG GET} 那一侧的用法
     */
    public static boolean matches(String pattern, String text, boolean nocase) {
        byte[] pat = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] s = text.getBytes(StandardCharsets.UTF_8);
        return matchLen(pat, 0, pat.length, s, 0, s.length, nocase);
    }

    /**
     * 游标与长度都是<em>字节</em>：{@code p}/{@code si} 是数组下标，{@code patLen}/{@code stringLen}
     * 是"还剩几个字节"，与 C 里那对 {@code char *} 指针加 {@code int} 长度一一对应。
     */
    private static boolean matchLen(byte[] pat, int p, int patLen,
                                    byte[] s, int si, int stringLen, boolean nocase) {
        while (patLen > 0 && stringLen > 0) {
            int pc = at(pat, p);
            if (pc == '*') {
                while (at(pat, p + 1) == '*') {                  // 连写的 * 先折叠
                    p++;
                    patLen--;
                }
                if (patLen == 1) {
                    return true;                                 // 以 * 收尾 ⇒ 已经吃掉剩下全部
                }
                while (stringLen > 0) {
                    if (matchLen(pat, p + 1, patLen - 1, s, si, stringLen, nocase)) {
                        return true;
                    }
                    si++;
                    stringLen--;
                }
                return false;
            }
            if (pc == '?') {
                // C 在这里还有一句 "if (stringLen == 0) return 0"：外层 while 已经保证了
                // stringLen > 0，那一句打不到（等价代码，不写）。
                si++;
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
                    int c = at(pat, p);
                    if (c == '\\' && patLen >= 2) {
                        p++;
                        patLen--;
                        if (at(pat, p) == at(s, si)) {
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
                        int cur = at(s, si);
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
                        if (lower(c) == lower(at(s, si))) {
                            match = true;
                        }
                    } else {
                        if (c == at(s, si)) {
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
                si++;
                stringLen--;
            } else {
                if (pc == '\\' && patLen >= 2) {                 // 集外的 \：下一字节是字面量
                    p++;
                    patLen--;
                    pc = at(pat, p);
                }
                int sc = at(s, si);
                boolean same = nocase ? lower(pc) == lower(sc) : pc == sc;
                if (!same) {
                    return false;
                }
                si++;
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

    /**
     * C 在越界处读到的是结尾的 {@code '\0'}；这里如实带回 0。
     * 命中时下标在界内，交回的是 Java {@code byte} 提升成 {@code int} 的那个<em>带符号</em>
     * 值（-128..127）—— 不 {@code & 0xFF}，因为 C 在那个平台上读到的就是带符号值，
     * 上面"符号"那一格是 4428 格实测钉住的。
     */
    private static int at(byte[] a, int idx) {
        return idx >= 0 && idx < a.length ? a[idx] : 0;
    }

    /** C 默认区域下的 {@code tolower}：只动 {@code A-Z}，其余（含带符号的负字节）原样。 */
    private static int lower(int c) {
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }
}
