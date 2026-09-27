package com.zifang.z.cache.core.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动挡那一条判据本身的表（{@code server.c:1301-1315}）。
 * <p>
 * 这里只问"该不该重写"这一件事，所以整张表都是纯函数 {@code shouldAutoRewrite} 的输入输出，
 * 不起服务器也不等定时器 —— 那两半各有自己的判据：实例接线在本文件后半（{@code checkAutoRewrite}
 * 读的是实例自己那四个数，不是表里传进去的那四个），"定时器真的每 100ms 来问一次"在
 * {@code RedisServerLifecycleTest} 的 socket 那一跑。
 * </p>
 */
class AofAutoRewriteTest {

    /** 一格判红的信息都在这一行里：label、期望、实际、为什么。 */
    private static void expectCell(Map<String, String> seen, Map<String, String> wrong, String label,
                                   Cell cell, String expected, String why) {
        String actual;
        try {
            actual = cell.read();
        } catch (Throwable t) {
            // 抛出来也算一个读数而不是让整条测试红在半路：底座为 0 那一格要的就是"不许除零"，
            // 把它记成"抛了 ArithmeticException"才能让量具看见是哪一格漏了。
            actual = "抛了 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        seen.put(label, actual);
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual + "。" + why);
        }
    }

    private interface Cell {
        String read() throws Exception;
    }

    private static String yesNo(boolean v) {
        return v ? "true" : "false";
    }

    /**
     * 逐条对上游那五个条件与那一步增幅。每一格都配一条<em>反方向</em>的邻居：
     * 只留一边的话"恒真"和"恒假"两种坏法都能全绿（例如只测"够增幅就该重写"，
     * 那么把整个函数改成 {@code return true} 也测不出来）。
     */
    @Test
    void growthRuleMatchesTheUpstreamCronCondition() {
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();

        // 1) aof_state == AOF_ON（:1302）
        expectCell(seen, wrong, "AOF 没开着 ⇒ 不重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(false, false, 1_000_000L, 100L, 100, 0L)),
                "false", "上游第一个条件就是 aof_state == AOF_ON；这一格与下一格合起来钉住\"开关在不在\"不是恒真");
        expectCell(seen, wrong, "AOF 开着且增幅够 ⇒ 重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 1_000_000L, 100L, 100, 0L)),
                "true", "同一组数只改 aofOn 这一项，两格必须一个真一个假");

        // 2) 没有子进程在重写（:1303-1304）
        expectCell(seen, wrong, "正在重写 ⇒ 不叠第二趟",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, true, 1_000_000L, 100L, 100, 0L)),
                "false", "上游挡的是 aof_child_pid != -1；我们那把对应标志是 rewriting。叠第二趟会把上一趟的中间态当数据集");

        // 3) aof_rewrite_perc 非零（:1305）
        expectCell(seen, wrong, "perc=0 是关掉而不是永远重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 1_000_000L, 100L, 0, 0L)),
                "false", "0 在上游是把整数当真假用；翻成 >0 或 >=0 的判断都会让 perc=0 变成\"每拍都重写\"");

        // 4) 体积地板用的是严格大于（:1306）
        expectCell(seen, wrong, "正好等于地板 ⇒ 不重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 100L, 50L, 100, 100L)),
                "false", "server.c:1306 是 aof_current_size > aof_rewrite_min_size，不是 >=");
        expectCell(seen, wrong, "超过地板一寸且增幅够 ⇒ 重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 101L, 50L, 100, 100L)),
                "true", "同上，多一个字节就该过；这一格是上一格的反向邻居");
        expectCell(seen, wrong, "地板没过时增幅再大也不谈",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 50L, 1L, 100, 1000L)),
                "false", "current=50 相对 base=1 的增幅是 4900%，但地板 1000 没过 —— 钉的是\"地板先看，增幅后算\"这个次序");

        // 5) growth 与门槛比的是 >=（:1310-1311）
        expectCell(seen, wrong, "增幅正好等于门槛 ⇒ 重写（>=）",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 200L, 100L, 100, 0L)),
                "true", "200*100/100-100 = 100，而 :1311 是 growth >= aof_rewrite_perc；写成 > 就在这格翻脸");
        expectCell(seen, wrong, "增幅差一个百分点 ⇒ 不重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 199L, 100L, 100, 0L)),
                "false", "199*100/100-100 = 99 < 100；这一格与上一格合起来把 >= 与 >、以及整除的那一步都钉住");
        expectCell(seen, wrong, "门槛降到 1 ⇒ 只涨一点也算",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 101L, 100L, 1, 100L)),
                "true", "同一个 101/100 只改门槛：100 时不重写（上一格）、1 时重写，钉的是门槛真的进了算式");

        // 6) base = aof_rewrite_base_size ?: 1（:1308-1309）
        expectCell(seen, wrong, "底座为 0 ⇒ 按 1 算而不是除零",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 512L, 0L, 100, 0L)),
                "true", "上游那句 ?: 1 不是装饰：整数除 0 在 C 里是 UB、在 Java 里是 ArithmeticException，"
                        + "那一拍只会留下一行日志而自动挡从此失灵");

        // 7) 上游默认值下的两格（100 / 64mb）
        long mb = 1024L * 1024L;
        expectCell(seen, wrong, "默认门槛下涨到 128mb（底座 64mb）⇒ 重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 128L * mb, 64L * mb,
                        AofPersistence.AUTO_AOF_REWRITE_PERCENTAGE, AofPersistence.AUTO_AOF_REWRITE_MIN_SIZE)),
                "true", "正好 100%，走 >= 那一边");
        expectCell(seen, wrong, "默认门槛下只涨到 127mb ⇒ 不重写",
                () -> yesNo(AofPersistence.shouldAutoRewrite(true, false, 127L * mb, 64L * mb,
                        AofPersistence.AUTO_AOF_REWRITE_PERCENTAGE, AofPersistence.AUTO_AOF_REWRITE_MIN_SIZE)),
                "false", "12700/64 整除得 198，减 100 是 98 —— 差 2 个百分点，不动");

        // 8) 两个旋钮的默认值本身（server.h:98-99）
        AofPersistence fresh = new AofPersistence();
        try {
            expectCell(seen, wrong, "默认百分比 100（server.h:98）",
                    () -> String.valueOf(fresh.getAutoAofRewritePercentage()), "100",
                    "AOF_REWRITE_PERC 就是 100");
            expectCell(seen, wrong, "默认地板 64mb（server.h:99）",
                    () -> String.valueOf(fresh.getAutoAofRewriteMinSize()), String.valueOf(64L * mb),
                    "AOF_REWRITE_MIN_SIZE 是 64*1024*1024");

            // 9) 越界：负数在配置那条路上就被拒（config.c:501-503 / :1160-1161 / :1262-1263），
            //    而且拒了之后<em>原值不许变</em>。
            expectCell(seen, wrong, "设负百分比 ⇒ 拒",
                    () -> {
                        try {
                            fresh.setAutoAofRewritePercentage(-1);
                            return "没拒，现在是 " + fresh.getAutoAofRewritePercentage();
                        } catch (IllegalArgumentException e) {
                            return "拒了且原值 " + fresh.getAutoAofRewritePercentage();
                        }
                    }, "拒了且原值 100",
                    "上游的区间下界是 0（config.c:1160-1161），负数进不了 server.aof_rewrite_perc");
            expectCell(seen, wrong, "设负地板 ⇒ 拒",
                    () -> {
                        try {
                            fresh.setAutoAofRewriteMinSize(-1L);
                            return "没拒，现在是 " + fresh.getAutoAofRewriteMinSize();
                        } catch (IllegalArgumentException e) {
                            return "拒了且原值 " + fresh.getAutoAofRewriteMinSize();
                        }
                    }, "拒了且原值 " + (64L * mb),
                    "config.c:1262-1263 的 memory field 下界同样是 0");
        } finally {
            closeQuietly(fresh);
        }

        assertTrue(wrong.isEmpty(),
                "自动挡的增幅判据有 " + wrong.size() + "/" + seen.size() + " 格判红：" + describe(wrong)
                        + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /**
     * {@link AofPersistence#checkAutoRewrite()} 读的是<em>实例自己</em>那四个数，
     * 而且真的会去排一趟重写。上一张表全过也挡不住"接线时把 fsyncedBytes 当 current 传过去"
     * 这一类坏法 —— 所以下面这几格走实例：{@code start()} 一份临时日志、真的 {@code onWrite} 几笔、
     * 然后问 {@code checkAutoRewrite()} 并看盘上那份日志换没换。
     */
    @Test
    void theInstancePathUsesItsOwnTwoSizes() throws Exception {
        Path dir = Files.createTempDirectory("zcache-aof-auto-rewrite-instance");
        Path aof = dir.resolve("appendonly.aof");
        AofPersistence a = new AofPersistence();
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try {
            a.start(aof.toString());
            a.setStoreAccessor(new MemoryStoreAccessor(new com.zifang.z.cache.core.storage.MemoryStore()));
            // 起步：空文件 ⇒ current=0、base=0。地板设 0、门槛设 100。
            a.setAutoAofRewriteMinSize(0L);
            a.setAutoAofRewritePercentage(100);

            expectCell(seen, wrong, "一个字都还没写 ⇒ 不重写",
                    () -> yesNo(a.checkAutoRewrite()), "false",
                    "current=0 过不了 :1306 那个严格大于 —— 这一格是\"没数据也每拍重写\"那种坏法的反证");

            byte[] padding = pad(400);
            for (int i = 0; i < 4; i++) {
                a.onWrite(new String[]{"SET", "k", new String(padding, StandardCharsets.UTF_8)});
            }
            long sizeBeforeSwap = Files.size(aof);
            expectCell(seen, wrong, "写了四笔（同一把键）⇒ 该重写",
                    () -> yesNo(a.checkAutoRewrite()), "true",
                    "base 停在 start() 那一刻的 0、current 是四笔的长度，:1308-1309 那句 ?: 1 让增幅是个天文数字");
            expectCell(seen, wrong, "重写排上了 ⇒ 标志立起来",
                    () -> yesNo(a.isRewriting()), "true",
                    "rewriteAsync 必须在入队之前抢标志（13i 钉过），否则下一拍又排一趟");
            String wait = awaitRewrite(a);
            long sizeAfterSwap = Files.size(aof);
            expectCell(seen, wrong, "盘上那份真的换小了（同一把键四笔只留一笔）",
                    () -> String.valueOf(sizeAfterSwap < sizeBeforeSwap), "true",
                    "这一格<em>不读我们自己的任何记账</em>：它问的是 java.nio 眼里的文件长度。"
                            + "四笔同键重写成一笔，长度必须掉下来。" + wait);
            expectCell(seen, wrong, "换完之后 current 等于新长度",
                    () -> String.valueOf(a.getAofCurrentSize()), String.valueOf(sizeAfterSwap),
                    "aof.c:1772 的 aofUpdateCurrentSize 按 stat 重取");
            expectCell(seen, wrong, "换完之后 base 等于新长度",
                    () -> String.valueOf(a.getAofBaseSize()), String.valueOf(sizeAfterSwap),
                    "aof.c:1773 就在下一行；底座不挪，下一拍拿旧底座算增幅会立刻再重写一次");
            expectCell(seen, wrong, "换完之后再来一拍 ⇒ 不叠第二趟",
                    () -> yesNo(a.checkAutoRewrite()), "false",
                    "current == base ⇒ 增幅 0，够不上 100。这一格是\"重写风暴\"那种坏法的反证");

            // perc 归 0 之后，写到再多也不许换
            long sizeAtSwap = sizeAfterSwap;
            long baseAtSwap = a.getAofBaseSize();
            a.setAutoAofRewritePercentage(0);
            for (int i = 0; i < 8; i++) {
                a.onWrite(new String[]{"SET", "k2", new String(pad(400), StandardCharsets.UTF_8)});
            }
            expectCell(seen, wrong, "perc=0 之后写八笔 ⇒ 还是不重写",
                    () -> yesNo(a.checkAutoRewrite()), "false",
                    "server.c:1305 那一项短路；这一格与上面\"该重写\"那格用的是同一类数据，只差旋钮");
            expectCell(seen, wrong, "那八笔确实落到了盘上（长度比换完那一刻大）",
                    () -> yesNo(Files.size(aof) > sizeAtSwap), "true",
                    "反证\"上一格的没重写只是因为没数据\"。这一格也只问 java.nio：追加路径每写完一笔都 "
                            + "flush 一次（AofPersistence 的 writer.flush()），所以盘上长度当场就该涨");
            expectCell(seen, wrong, "perc=0 期间底座一步没挪 ⇒ 确实没换过文件",
                    () -> String.valueOf(a.getAofBaseSize()), String.valueOf(baseAtSwap),
                    "底座只在载入与重写两处会动（aof.c:866/:1773），它一动就说明有人重写过");
        } finally {
            closeQuietly(a);
            deleteTree(dir);
        }

        assertTrue(wrong.isEmpty(),
                "自动挡的实例接线有 " + wrong.size() + "/" + seen.size() + " 格判红：" + describe(wrong)
                        + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /** 等重写那一份跑完：只看 {@code rewriting} 标志归还，超时不判红而是让下面那格带着这句话红。 */
    private static String awaitRewrite(AofPersistence a) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (a.isRewriting() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        List<String> notes = new ArrayList<>();
        if (a.isRewriting()) {
            notes.add("等 rewriting 标志归还超时；");
        }
        return String.join("", notes);
    }

    private static byte[] pad(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    private static String describe(Map<String, String> wrong) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : wrong.entrySet()) {
            sb.append("\n  - ").append(e.getKey()).append("：").append(e.getValue());
        }
        return sb.toString();
    }

    private static void closeQuietly(AofPersistence a) {
        try {
            a.shutdown();
        } catch (IOException | RuntimeException e) {
            // 收尾失败不该盖掉上面那一行的读数
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            paths.forEach(all::add);
            all.sort((x, y) -> y.getNameCount() - x.getNameCount());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }
}
