package com.zifang.z.cache.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README 里每一条定量主张都必须能从源码重算出来 —— 广告出去的话就是承诺。
 *
 * <p>为什么要有这一层：本仓 README 的结构树把每个模块的类数写死了
 * （common 14 / core 27 / client 16 / server 2 / starter 6），而每加一个类都会漂一次。
 * 没有任何尺子去问源码「现在还是吗」，那这些数字的保质期就是上一次编辑的时刻。</p>
 *
 * <p>口径三条：</p>
 * <ol>
 *   <li><b>只数 src/main</b>：测试类不是 README 说的「类」；</li>
 *   <li><b>主张抠不到就红</b>：不许「正则没匹配上就跳过」，那正是空跑；</li>
 *   <li><b>按结构树的模块名定位，不按数字定位</b>：数字是结论不是锚点。
 *       早期版本把「6 个类」写成定位标签，结果它匹配上了「16 个类」那一行 ——
 *       尺子先失灵、后冤枉健康页，这是尺子自己的 bug，不是 README 的问题。</li>
 * </ol>
 *
 * <p>数值不等 = 红且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 尺自己的牙口由 {@link #rulerActuallyCondemnsWrongNumbers()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #readmeClaimsMatchTheCode()} 本身证明。</p>
 */
class ReadmeClaimsTest {

    /** 根 pom 的 {@code <modules>} 声明顺序即 README 结构树的列序。 */
    private static final String[] MODULES = {
            "z-cache-common", "z-cache-core", "z-cache-client",
            "z-cache-server", "z-cache-spring-boot-starter",
    };

    /** starter 模块在 README 结构树里的节点名（节点名 ≠ 目录名，多一个 {@code spring-boot} 段）。 */
    private static final String STARTER_NODE = "z-cache-spring-boot-starter";

    /** 仓根：向上找同时有 README.md 与 pom.xml 的目录（测试跑在模块目录，user.dir 不是仓根）。 */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("README.md")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        throw new IllegalStateException("定位仓根失败：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(Path f) {
        try {
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String norm(Path p) {
        return p.toString().replace('\\', '/');
    }

    /**
     * 定位 README 结构树里某个节点的整行。
     *
     * <p>锚点是<b>树形前缀 + 节点名</b>，不是数字：
     * 数字会漂，且短数字是长数字的子串（「6 个类」⊂「16 个类」）⇒ 定位到别人的行上去。
     * 树形前缀（{@code ├──} / {@code └──}）还能把正文里 {@code z-cache-server/target/...}
     * 这类命令示例排除掉。</p>
     */
    private static Matcher treeLine(String readme, String node) {
        // 节点名之后必须是「/」（目录）或空白/#（文件），否则 z-cache-core 会误配 z-cache-core-extra。
        return Pattern.compile("(?m)^[ \\t]*(?:├──|└──)[ \\t]*" + Pattern.quote(node)
                        + "(?:/|(?=[ \\t#]))[^\\n]*$")
                .matcher(readme);
    }

    private static String requireTreeLine(String readme, String node) {
        Matcher m = treeLine(readme, node);
        if (!m.find()) {
            throw new AssertionError("README 的结构树里找不到节点行：" + node
                    + "（结构树被改过形？还是这行被挪走了？）");
        }
        return m.group();
    }

    /** 某模块 src/main 下的 .java 个数（不含 src/test —— 测试类不是 README 说的「类」）。 */
    private static int mainClassCount(Path root, String module) {
        Path d = root.resolve(module).resolve("src/main");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + module + "/src/main");
        }
        try (Stream<Path> s = Files.walk(d)) {
            return (int) s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !norm(p).contains("/target/"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** starter 模块 src/main 下的类名集合。 */
    private static Set<String> starterClassNames(Path root) {
        Path d = root.resolve(STARTER_NODE).resolve("src/main");
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + STARTER_NODE + "/src/main");
        }
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 解析根 pom 的<b>项目自身</b> version / module 数 / parent version。
     *
     * <p>用 XML 解析而不是正则：根 pom 里同时有 parent 的 {@code <version>1.1.0</version>} 与
     * 自身的 {@code <version>1.3.6</version>}，正则会抓错；XML 解析还天然排除被
     * {@code <!-- -->} 注释掉的 module。</p>
     */
    private static Element projectElement(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("解析 pom 失败：" + pom, e);
        }
    }

    private static Element childElement(Element project, String name) {
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String projectVersion(Path root) {
        Element v = childElement(projectElement(root.resolve("pom.xml")), "version");
        if (v == null) {
            throw new IllegalStateException("根 pom 里没有项目自身的 <version>");
        }
        return v.getTextContent().trim();
    }

    private static String parentVersion(Path root) {
        Element parent = childElement(projectElement(root.resolve("pom.xml")), "parent");
        if (parent == null) {
            throw new IllegalStateException("根 pom 里没有 <parent>");
        }
        Element v = childElement(parent, "version");
        if (v == null) {
            throw new IllegalStateException("根 pom 的 <parent> 里没有 <version>");
        }
        return v.getTextContent().trim();
    }

    private static int moduleCount(Path root) {
        Element modules = childElement(projectElement(root.resolve("pom.xml")), "modules");
        if (modules == null) {
            throw new IllegalStateException("根 pom 里没有 <modules>");
        }
        return modules.getElementsByTagName("module").getLength();
    }

    /** 读源码里某个 int 常量的字面值。 */
    private static int sourceIntConstant(Path root, String relativeJava, String constName) {
        Path f = root.resolve(relativeJava);
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("源码文件不存在：" + relativeJava);
        }
        Matcher m = Pattern.compile("(?m)\\b" + Pattern.quote(constName) + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(read(f));
        if (!m.find()) {
            throw new IllegalStateException("源码里找不到常量 " + constName + "：" + relativeJava);
        }
        return Integer.parseInt(m.group(1));
    }

    /** 一次重算出的全部数字。 */
    private static final class Counts {
        final int[] perModule;
        final int modules;
        final String version;
        final String parentVersion;
        final Set<String> starterClassNames;
        final int defaultPort;
        final int defaultDbCount;

        Counts(int[] perModule, int modules, String version, String parentVersion,
                Set<String> starterClassNames, int defaultPort, int defaultDbCount) {
            this.perModule = perModule;
            this.modules = modules;
            this.version = version;
            this.parentVersion = parentVersion;
            this.starterClassNames = starterClassNames;
            this.defaultPort = defaultPort;
            this.defaultDbCount = defaultDbCount;
        }
    }

    private static Counts recomputed() {
        Path root = repoRoot();
        int[] perModule = new int[MODULES.length];
        for (int i = 0; i < MODULES.length; i++) {
            perModule[i] = mainClassCount(root, MODULES[i]);
        }
        return new Counts(perModule, moduleCount(root), projectVersion(root), parentVersion(root),
                starterClassNames(root),
                sourceIntConstant(root, "z-cache-core/src/main/java/com/zifang/z/cache/core/server/RedisServer.java",
                        "DEFAULT_PORT"),
                sourceIntConstant(root, "z-cache-core/src/main/java/com/zifang/z/cache/core/storage/MemoryStore.java",
                        "DEFAULT_DB_COUNT"));
    }

    /** README 里逐条抠出数字并与重算值对账；不一致就抛 AssertionError 并把真数打进去。 */
    private static void verifyClaims(String readme, Counts c) {
        // 主张 1：结构树里每个模块的类数。
        //   z-cache-common/              # 14 个 src/main 类：…
        //   z-cache-core/                # 27 个类：…
        //   z-cache-client/              # 16 个类：…
        //   z-cache-server/              # 2 个类：…
        //   z-cache-spring-boot-starter/ # 6 个类：…
        for (int i = 0; i < MODULES.length; i++) {
            String line = requireTreeLine(readme, MODULES[i]);
            Matcher num = Pattern.compile("(\\d+)\\s*个").matcher(line);
            if (!num.find()) {
                throw new AssertionError("README 结构树的 " + MODULES[i]
                        + " 那一行抠不出「N 个」： " + line);
            }
            assertEquals(c.perModule[i], Integer.parseInt(num.group(1)),
                    "模块 " + MODULES[i] + " 的 src/main 类数对不上");
        }

        // 主张 2：starter 的类名清单 —— 双向对账。
        // 只查「README 提到的类是否存在」会漏掉「源码多出来的类没写进 README」，
        // 所以两边都比：README 里多一个、或少一个，都要红。
        String starterLine = requireTreeLine(readme, STARTER_NODE);
        Set<String> readmeNames = parseStarterNames(starterLine);
        Set<String> missing = new TreeSet<>(c.starterClassNames);
        missing.removeAll(readmeNames);
        Set<String> extra = new TreeSet<>(readmeNames);
        extra.removeAll(c.starterClassNames);
        if (!missing.isEmpty()) {
            throw new AssertionError("starter 的 src/main 里有这些类，但 README 结构树没列： " + missing);
        }
        if (!extra.isEmpty()) {
            throw new AssertionError("README 结构树列了这些 starter 类，但 src/main 下没有： " + extra);
        }

        // 主张 3：「`z-cache` 及 5 个子件」
        Matcher m = Pattern.compile("`z-cache` 及 (\\d+) 个子件").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「`z-cache` 及 N 个子件」这条主张");
        }
        assertEquals(c.modules, Integer.parseInt(m.group(1)), "Maven 模块数对不上（根 pom <modules> 条数）");

        // 主张 4：根聚合 POM 的版本
        String pomLine = requireTreeLine(readme, "pom.xml");
        m = Pattern.compile("版本 (\\d+\\.\\d+\\.\\d+)").matcher(pomLine);
        if (!m.find()) {
            throw new AssertionError("README 结构树的 pom.xml 那一行抠不出「版本 X.Y.Z」： " + pomLine);
        }
        assertEquals(c.version, m.group(1), "版本号对不上（根 pom 项目自身的 <version>）");

        // 主张 5：根聚合 POM 继承的 parent 版本
        m = Pattern.compile("z-boot-parent:(\\d+\\.\\d+\\.\\d+)").matcher(pomLine);
        if (!m.find()) {
            throw new AssertionError("README 结构树的 pom.xml 那一行抠不出「z-boot-parent:X.Y.Z」： " + pomLine);
        }
        assertEquals(c.parentVersion, m.group(1), "父项目版本对不上（根 pom <parent> 的 <version>）");

        // 主张 6：默认端口（源码常量 RedisServer.DEFAULT_PORT）
        m = Pattern.compile("\\*\\*默认端口\\*\\*\\s*\\|\\s*`(\\d+)`").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 的基本信息表里找不到「默认端口 | `N`」这条主张");
        }
        assertEquals(c.defaultPort, Integer.parseInt(m.group(1)),
                "默认端口对不上（源码常量 RedisServer.DEFAULT_PORT）");

        // 主张 7：数据库数（源码常量 MemoryStore.DEFAULT_DB_COUNT）
        m = Pattern.compile("\\*\\*数据库数\\*\\*\\s*\\|\\s*(\\d+)").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 的基本信息表里找不到「数据库数 | N」这条主张");
        }
        assertEquals(c.defaultDbCount, Integer.parseInt(m.group(1)),
                "数据库数对不上（源码常量 MemoryStore.DEFAULT_DB_COUNT）");
    }

    /** 从 starter 那一行里抠出类名清单（行尾 "个类：Name1/Name2/Name3"）。 */
    private static Set<String> parseStarterNames(String starterLine) {
        Matcher m = Pattern.compile("个类：([^\\n]*)").matcher(starterLine);
        if (!m.find()) {
            throw new AssertionError("starter 那一行抠不出「个类：」后面的类名清单： " + starterLine);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : m.group(1).split("/")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                names.add(t);
            }
        }
        if (names.isEmpty()) {
            throw new AssertionError("starter 那一行的类名清单是空的： " + starterLine);
        }
        return names;
    }

    /** 把某个模块结构树行的「N 个」改成别的数，返回篡改后的整篇 README。 */
    private static String tamperModuleCount(String readme, String module, int newCount) {
        Matcher m = treeLine(readme, module);
        if (!m.find()) {
            throw new IllegalStateException("找不到模块行：" + module);
        }
        String line = m.group();
        String tampered = line.replaceFirst("(\\d+)\\s*个", newCount + " 个");
        if (tampered.equals(line)) {
            throw new IllegalStateException("该行抠不出「N 个」：" + line);
        }
        return readme.substring(0, m.start()) + tampered + readme.substring(m.end());
    }

    /** 把根聚合 POM 结构树行的版本改成别的值。 */
    private static String tamperVersion(String readme, String newVersion) {
        Matcher m = treeLine(readme, "pom.xml");
        if (!m.find()) {
            throw new IllegalStateException("找不到 pom.xml 结构树行");
        }
        String line = m.group();
        String tampered = line.replaceFirst("版本 (\\d+\\.\\d+\\.\\d+)", "版本 " + newVersion);
        if (tampered.equals(line)) {
            throw new IllegalStateException("该行抠不出「版本 X.Y.Z」：" + line);
        }
        return readme.substring(0, m.start()) + tampered + readme.substring(m.end());
    }

    /** 把 starter 结构树行里的某个类名抹掉（模拟「源码多了一个类但 README 没更新」）。 */
    private static String tamperStarterNames(String readme, String className) {
        Matcher m = treeLine(readme, STARTER_NODE);
        if (!m.find()) {
            throw new IllegalStateException("找不到 starter 结构树行");
        }
        String line = m.group();
        String tampered = line.replace("/" + className, "");
        if (tampered.equals(line)) {
            throw new IllegalStateException("该行里找不到类名 " + className + "：" + line);
        }
        return readme.substring(0, m.start()) + tampered + readme.substring(m.end());
    }

    @Test
    @DisplayName("README 的定量主张与源码重算值逐条对账")
    void readmeClaimsMatchTheCode() {
        Path root = repoRoot();
        Counts c = recomputed();
        verifyClaims(read(root.resolve("README.md")), c);
    }

    @Test
    @DisplayName("尺子的牙口：把 README 的数改错，判定必须判红")
    void rulerActuallyCondemnsWrongNumbers() {
        String readme = read(repoRoot().resolve("README.md"));
        Counts c = recomputed();

        // 变异 1：模块类数改错
        String tamperedCount = tamperModuleCount(readme, "z-cache-core", c.perModule[1] + 1);
        assertNotEquals(readme, tamperedCount, "篡改失败：z-cache-core 的结构树行变了形？");
        AssertionError err1 = assertThrows(AssertionError.class,
                () -> verifyClaims(tamperedCount, c));
        assertTrue(err1.getMessage().contains("类数对不上")
                        && err1.getMessage().contains("z-cache-core"),
                "报错要指向真正对不上的那条主张，实际是：" + err1.getMessage());

        // 变异 2：版本改错
        String tamperedVersion = tamperVersion(readme, "9.9.9");
        AssertionError err2 = assertThrows(AssertionError.class,
                () -> verifyClaims(tamperedVersion, c));
        assertTrue(err2.getMessage().contains("版本号对不上"),
                "版本改错必须判红，实际是：" + err2.getMessage());

        // 变异 3：starter 类名清单少一个
        String dropped = c.starterClassNames.iterator().next();
        String tamperedNames = tamperStarterNames(readme, dropped);
        AssertionError err3 = assertThrows(AssertionError.class,
                () -> verifyClaims(tamperedNames, c));
        assertTrue(err3.getMessage().contains(dropped),
                "类名清单少一个必须报出缺的那个类，实际是：" + err3.getMessage());
    }

    @Test
    @DisplayName("不许冤枉健康页：真实 README 不得被判红")
    void healthyReadmeIsNotCondemned() {
        String readme = read(repoRoot().resolve("README.md"));
        verifyClaims(readme, recomputed());
    }
}
