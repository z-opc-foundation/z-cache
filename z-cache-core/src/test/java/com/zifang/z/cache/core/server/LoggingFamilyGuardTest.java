package com.zifang.z.cache.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 13u —— "日志只有 log4j2 这一族"这句话的四条落地点，外加一条<em>真的把事件送到 appender</em>的运行时证明。
 *
 * <h2>为什么需要这一支，而不是"看 pom 一眼"</h2>
 *  foundation 那一层的 README 早就写着 {@code 日志 | Log4j2 (排除 Logback)}，而 13u 之前的实际类路径是：
 * {@code z-cache-core} 把 {@code ch.qos.logback:logback-classic:1.2.13} 以 <b>compile</b> 作用域
 * 声明出去（每个消费者继承一份 logback），{@code z-cache-client} 再以 {@code provided} 声明一遍；
 * 代码里那 16 个走 log4j2 的文件<em>全部</em> {@code import org.apache.logging.log4j}，一处 slf4j / logback 都没有
 * （另有 2 个文件整支走 {@code java.util.logging}，见下面第 5 条）；
 * 而全仓<em>没有</em> {@code log4j-core} ⇒ log4j2 那一侧只有 API 没有实现。
 * 再叠上两处：{@code z-cache-core/src/main/resources/logback.xml} 是<em>库 jar 里的 root logger 配置</em>
 * （它把 {@code logs/z-cache.log} 按进程工作目录写出去 —— 本轮动手前盘上真有三个这样的目录在被测试跑写：
 * {@code z-cache-core/logs/}、{@code z-cache-client/logs/}、仓根 {@code logs/}）；
 * {@code spring-boot-starter-test} 又传递带进 {@code spring-boot-starter-logging}
 * （logback + {@code log4j-to-slf4j:2.25.4}，于是同一个测试类路径上出现过 2.17.2 的 api 与 2.25.4 的桥，
 * 而且方向是 log4j2 ⇒ slf4j ⇒ logback，与广告正相反）。
 *
 * <h2>这一支钉的四件事，以及各自"红法"</h2>
 * <ol>
 *   <li>{@link #onlyLog4j2IsDeclaredAcrossTheReactor()} —— 解析全部 6 份 pom 的<em>结构</em>（不是 grep：
 *       注释里、{@code <exclusions>} 里都出现 "logback" 字样，文本判定必然假红）。拔掉 core 那支
 *       log4j 声明 ⇒ 红；把 logback 加回来 ⇒ 红。</li>
 *   <li>{@link #springDefaultLoggingIsExcludedWhereSpringIsOnTheClasspath()} —— 钉那条 {@code <exclusion>}
 *       真的挂在 {@code spring-boot-starter-test} 上。摘掉它 ⇒ 红。</li>
 *   <li>{@link #loggingConfigurationLivesInTheAppNotInTheLibrary()} —— 配置文件必须在
 *       {@code z-cache-server}，库模块一份都不许有。把 {@code logback.xml} 放回 core ⇒ 红。</li>
 *   <li>{@link #noLoggingConfigPropertyIsPointedAtAClasspathResource()} —— 不许在 properties/yml 里留
 *       {@code logging.config=classpath:...}。这一条是否定式，所以自带<em>猎物</em>：同一个扫描函数
 *       先喂一行必然命中的样本，量不出命中就不许宣布"没有"。</li>
 *   <li>{@link #mainSourcesThatLogDoItThroughTheOneFamily()} —— "所有模块统一使用 log4j2"里的<em>代码</em>那一半。
 *       13u 动手时 {@code src/main} 里有 16 个文件走 log4j2、<em>还有 2 个文件走 {@code java.util.logging}</em>
 *       （{@code AofPersistence} 18 处、{@code RdbPersistence} 11 处，合计 <b>29</b> 个记日志调用点）。
 *       那两家的行既不进 log4j2 的
 *       排版、也不进 {@code logs/z-cache.log}，而是被 JUL 自己的 {@code SimpleFormatter} 打进 stderr ——
 *       这一条不是推演：13u 真起过一次发行包，控制台里两种时间戳格式（{@code 2026-09-27 22:49:54.933 [main] INFO …}
 *       与 {@code Sep 27, 2026 10:49:54 PM … \n INFO: …}）并排出现。判据扫的是代码行里的包名而不是只看
 *       {@code import}（全限定名不带 import 也一样能用），并且双向对照：现造的真 import 必须读到，
 *       同样的字样只写在注释里必须<em>不</em>读到。</li>
 * </ol>
 * 最后一条 {@link #eventsActuallyReachAnAppenderThroughTheLog4j2Implementation()} 是这支尺存在的理由：
 * 前<em>五</em>条全绿也<em>不保证</em>运行时真有一个实现 —— 2.17.2 的 API 在没有 {@code log4j-core} 时会
 * 降级到 {@code SimpleLogger} 并只在状态通道里抱怨一句，界面上一切正常、日志静默。这里把
 * {@code LogManager.getLogger()} 拿到的对象钉成 log4j-core 的实现，再真发一条事件、要求那个
 * 实现把它送进一个 appender。把 core pom 里那支 test 作用域的 {@code log4j-core} 摘掉，这一条必红。
 *
 * <h2>这支尺看不见的那一面（不冒充全覆盖）</h2>
 * 它读的是<em>声明</em>，不是解析后的类路径：假如将来某个新依赖把 logback <em>传递</em>进来，
 * 这四条 pom 断言不会红。那一面由 {@code mvn -o -B dependency:tree} 兜（13u 归档在
 * {@code ~/.cache/zcache_gauges/logs/deptree_log4j_{before,after}.log}：before 有 18 行 logging，
 * after 里 logback / {@code spring-boot-starter-logging} / {@code log4j-to-slf4j} 命中 0，
 * log4j2 只剩 2.17.2 一个版本），记名在这里，不假装测试也管得着。
 */
public class LoggingFamilyGuardTest {

    /** 判定"这一支依赖属不属于日志家族"的那张表：命中它就必须是 log4j2 那一家。 */
    private static final List<String> LOGGING_GROUP_PREFIXES =
            Arrays.asList("ch.qos.logback", "org.slf4j", "org.apache.logging.slf4j", "org.apache.logging.log4j");

    /** 属于日志家族、但不允许作为<em>依赖声明</em>出现的 artifactId（log4j2 那一家之外的全都算）。 */
    private static final List<String> FORBIDDEN_ARTIFACTS =
            Arrays.asList("logback-classic", "logback-core", "slf4j-api", "slf4j-simple", "jul-to-slf4j",
                          "log4j-to-slf4j", "spring-boot-starter-logging");

    private static File reactorRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int hops = 0; dir != null && hops < 6; hops++, dir = dir.getParentFile()) {
            File pom = new File(dir, "pom.xml");
            if (!pom.isFile()) {
                continue;
            }
            String text;
            try {
                text = new String(Files.readAllBytes(pom.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new AssertionError("读不到 " + pom + "，这一支尺没法跑", e);
            }
            if (text.contains("<module>z-cache-client</module>")
                    && text.contains("<artifactId>z-cache</artifactId>")) {
                return dir;
            }
        }
        throw new AssertionError("从 " + System.getProperty("user.dir")
                + " 往上找不到 z-cache 的聚合 pom —— 这一支尺要求在被测量的仓里跑，不许静默跳过");
    }

    private static List<File> reactorPoms(File root) {
        List<File> out = new ArrayList<>();
        out.add(new File(root, "pom.xml"));
        File[] kids = root.listFiles();
        assertNotNull(kids, "聚合 pom 所在目录列不出来：" + root);
        for (File kid : kids) {
            File pom = new File(kid, "pom.xml");
            if (kid.isDirectory() && pom.isFile()) {
                out.add(pom);
            }
        }
        return out;
    }

    /**
     * 收集 {@code <dependency>} 的 {@code groupId:artifactId}。
     * {@code <dependencyManagement>} 里的也算（那里出现 logback 同样是"给全仓开了一条能误引的路"），
     * 而 {@code <exclusion>} 用的是另一个标签名，天然不会混进来 —— 那正是我们要能<em>区分</em>的两件事。
     */
    private static List<String> declaredDependencies(List<File> poms) {
        List<String> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            for (File pom : poms) {
                Document doc = builder.parse(pom);
                NodeList nodes = doc.getElementsByTagName("dependency");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    if (!(node instanceof Element)) {
                        continue;
                    }
                    String g = firstText((Element) node, "groupId");
                    String a = firstText((Element) node, "artifactId");
                    if (g != null && a != null) {
                        out.add(g + ":" + a);
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("pom 解析失败，这一支尺没法宣布任何结论", e);
        }
        return out;
    }

    private static String firstText(Element parent, String tag) {
        NodeList kids = parent.getElementsByTagName(tag);
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getParentNode() == parent) {
                String t = kids.item(i).getTextContent();
                return t == null ? null : t.trim();
            }
        }
        return null;
    }

    /** 扫过一遍文本，返回所有以 {@code logging.config=} 开头的行 —— 第 4 条与它的猎物共用这一个函数。 */
    private static List<String> loggingConfigLines(Iterable<File> files) throws Exception {
        List<String> hits = new ArrayList<>();
        for (File f : files) {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("logging.config=") || t.startsWith("logging.config :")) {
                    hits.add(f + " -> " + t);
                }
            }
        }
        return hits;
    }

    private static List<File> configCandidates(File root) throws Exception {
        List<File> out = new ArrayList<>();
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.sorted()::iterator) {
                String s = p.toString();
                if (s.contains("/target/") || s.contains("/.git/")) {
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (s.endsWith(".properties") || s.endsWith(".yml")) {
                    out.add(p.toFile());
                }
            }
        }
        return out;
    }

    @Test
    public void onlyLog4j2IsDeclaredAcrossTheReactor() throws Exception {
        File root = reactorRoot();
        List<File> poms = reactorPoms(root);
        List<String> declared = declaredDependencies(poms);

        // 阳性对照（这条尺自己也得先证明它看得见东西）：pom 份数与依赖总数都在预期量级上，
        // 且 log4j-api 这一支必须<em>读得到</em> —— 否则"没有 logback"可能只是"什么都没读到"。
        assertEquals(6, poms.size(), "反应堆里应该有 6 份 pom（聚合 + 5 个模块），读到 " + poms);
        assertTrue(declared.size() >= 25,
                   "只解析出 " + declared.size() + " 条依赖 ⇒ 解析器或路径不对，下面的结论没有资格成立");
        assertTrue(declared.contains("org.apache.logging.log4j:log4j-api"),
                   "log4j-api 读不到 ⇒ 这一族已经被整条摘掉了，阳性对照不成立。读到的是：" + declared);

        List<String> offenders = new ArrayList<>();
        for (String d : declared) {
            boolean loggingFamily = false;
            for (String prefix : LOGGING_GROUP_PREFIXES) {
                if (d.startsWith(prefix + ":")) {
                    loggingFamily = true;
                    break;
                }
            }
            if (d.startsWith("org.springframework.boot:") && d.endsWith("spring-boot-starter-logging")) {
                loggingFamily = true;
            }
            if (!loggingFamily) {
                continue;
            }
            String artifact = d.substring(d.indexOf(':') + 1);
            boolean log4j2 = d.startsWith("org.apache.logging.log4j:")
                    || artifact.equals("spring-boot-starter-log4j2");
            if (!log4j2 || FORBIDDEN_ARTIFACTS.contains(artifact)) {
                offenders.add(d);
            }
        }
        assertTrue(offenders.isEmpty(),
                   "这些依赖不属于 log4j2 那一家（或就是被明令排除的那几个 artifactId）：" + offenders);
    }

    @Test
    public void springDefaultLoggingIsExcludedWhereSpringIsOnTheClasspath() throws Exception {
        File root = reactorRoot();
        List<File> poms = reactorPoms(root);
        int exclusions = 0;
        int springTestDeps = 0;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            for (File pom : poms) {
                Document doc = builder.parse(pom);
                NodeList deps = doc.getElementsByTagName("dependency");
                for (int i = 0; i < deps.getLength(); i++) {
                    Element dep = (Element) deps.item(i);
                    if (!"spring-boot-starter-test".equals(firstText(dep, "artifactId"))) {
                        continue;
                    }
                    springTestDeps++;
                    NodeList ex = dep.getElementsByTagName("exclusion");
                    for (int k = 0; k < ex.getLength(); k++) {
                        Element e = (Element) ex.item(k);
                        if ("spring-boot-starter-logging".equals(firstText(e, "artifactId"))) {
                            exclusions++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("pom 解析失败", e);
        }
        // 阳性对照：`spring-boot-starter-test` 本身读得到（读不到 ⇒ 循环根本没进去，exclusions=0 会假红成真红）
        assertEquals(1, springTestDeps,
                     "应该正好有一处 spring-boot-starter-test 声明（它才是把 spring 默认日志带进来的那一处）");
        assertEquals(1, exclusions,
                     "spring-boot-starter-test 上必须挂一条 spring-boot-starter-logging 的 exclusion；现读 "
                             + exclusions + " 条");
    }

    @Test
    public void loggingConfigurationLivesInTheAppNotInTheLibrary() throws Exception {
        File root = reactorRoot();
        List<String> logbackConfigs = new ArrayList<>();
        List<String> log4jConfigs = new ArrayList<>();
        collectConfigs(root, logbackConfigs, log4jConfigs);

        // 阳性对照：这一把扫<em>必须</em>扫得到 server 那份 log4j2.xml。扫不到就是路径写错了，
        // 而"库里没有配置"那条断言会在"什么都没扫到"上假通过。
        assertTrue(log4jConfigs.contains("z-cache-server/src/main/resources/log4j2.xml"),
                   "发行包那一份 log4j2.xml 扫不到（扫到的是 " + log4jConfigs + "）⇒ 下面那条结论没有依据");
        assertTrue(logbackConfigs.isEmpty(),
                   "库里不该再有 logback 的配置：" + logbackConfigs);
        assertEquals(1, log4jConfigs.size(),
                     "日志配置只该有一份、且在发行包那一层；现读：" + log4jConfigs);
    }

    private static void collectConfigs(File dir, List<String> logbackOut, List<String> log4jOut) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                if (!f.getName().equals("target") && !f.getName().equals(".git")) {
                    collectConfigs(f, logbackOut, log4jOut);
                }
                continue;
            }
            String name = f.getName();
            boolean logback = name.startsWith("logback");
            boolean log4j = name.startsWith("log4j2");
            if (!logback && !log4j) {
                continue;
            }
            String rel = relativeTo(f);
            // 只有落在 src/main/resources 或 src/test/resources 里的才算"配置"；
            // 文档/归档里同名副本不参与判定（本轮没有，但别把判据绑死在"只有一份文件"上）。
            if (!rel.contains("/resources/")) {
                continue;
            }
            (logback ? logbackOut : log4jOut).add(rel);
        }
    }

    /** 仓根的相对路径；拿不到仓根就退回绝对路径（宁可判据变红也不静默放行）。 */
    private static String relativeTo(File f) {
        try {
            return reactorRoot().toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    @Test
    public void noLoggingConfigPropertyIsPointedAtAClasspathResource() throws Exception {
        File root = reactorRoot();
        List<File> scanned = configCandidates(root);

        // 否定式断言要带猎物：同一个函数先吃一行必然命中的样本，量不出这一口就不许宣布"没有"。
        File prey = File.createTempFile("logging-family-prey", ".properties");
        prey.deleteOnExit();
        Files.write(prey.toPath(),
                    "logging.config=classpath:logback-spring.xml".getBytes(StandardCharsets.UTF_8));
        List<String> biting = loggingConfigLines(Arrays.asList(prey));
        assertEquals(1, biting.size(), "扫描函数连现造的样本都读不出 ⇒ 下面那条『没有』不成立：" + scanned.size());

        List<String> hits = loggingConfigLines(scanned);
        assertTrue(hits.isEmpty(),
                   "properties/yml 里不许留 logging.config（它会把实现又指回某个 classpath 配置）：" + hits);
        assertFalse(scanned.isEmpty(), "一个 properties/yml 都没扫到 ⇒ 先怀疑扫描范围，而不是庆祝通过");
    }

    /** 除了 log4j2，记日志只许这一家；出现第二家就是"统一"这句话没做到。 */
    private static final List<String> FOREIGN_LOGGING_PACKAGES =
            Arrays.asList("java.util.logging", "org.slf4j", "ch.qos.logback");

    @Test
    public void mainSourcesThatLogDoItThroughTheOneFamily() throws Exception {
        File root = reactorRoot();
        List<File> mains = mainJavaSources(root);
        assertFalse(mains.isEmpty(), "一份 src/main 的 java 都没扫到 ⇒ 先怀疑扫描范围，而不是庆祝通过");

        // 阳性对照（判据有牙）：同一个函数必须读得出一支现造的外来 logger。
        File prey = File.createTempFile("LoggingFamilyPrey", ".java");
        prey.deleteOnExit();
        write(prey, "package p;\n"
                   + "import java.util.logging.Logger;\n"
                   + "class C { private static final Logger L = Logger.getLogger(\"c\"); }\n");
        assertEquals(Arrays.asList("java.util.logging"), loggingPackagesUsedIn(prey),
                     "判据连现造的外来 logger 样本都读不出 ⇒ 下面那条『src/main 只用 log4j2』是假绿");

        // 反向对照（判据不吃文档）：同样的字样只出现在注释里，必须<em>不</em>算命中，
        // 否则这支尺会在将来谁写一句"这里以前用 java.util.logging"时把自己绊倒。
        File docOnly = File.createTempFile("LoggingFamilyDocOnly", ".java");
        docOnly.deleteOnExit();
        write(docOnly, "package p;\n"
                     + "/** 以前这里用 java.util.logging，后来统一到 org.apache.logging.log4j。 */\n"
                     + "// org.slf4j 也没有引用\n"
                     + "class C { void m() { } }\n");
        assertTrue(loggingPackagesUsedIn(docOnly).isEmpty(),
                   "注释里的包名不该被当成引用（读到的是 " + loggingPackagesUsedIn(docOnly) + "）");

        List<String> offenders = new ArrayList<>();
        int onLog4j2 = 0;
        for (File f : mains) {
            List<String> foreign = loggingPackagesUsedIn(f);
            if (!foreign.isEmpty()) {
                offenders.add(relativeTo(f) + " -> " + foreign);
            }
            if (!foreign.isEmpty() && usesLog4j2(f)) {
                offenders.add(relativeTo(f) + " -> 同文件混用 log4j2 与外来家族");
            }
            if (usesLog4j2(f)) {
                onLog4j2++;
            }
        }
        assertTrue(offenders.isEmpty(),
                   "src/main 里记日志只许 log4j2 一家（13u 之前 AofPersistence / RdbPersistence 这两个文件用的是 "
                           + "java.util.logging：它们的行既不进 console 那套排版、也不进 logs/z-cache.log，"
                           + "而是被 JUL 自己的 SimpleFormatter 打到 stderr）：" + offenders);
        assertTrue(onLog4j2 > 0,
                   "src/main 里一个 log4j2 logger 都没有（" + mains.size() + " 个文件全裸）⇒ 先怀疑扫描范围");
    }

    /** 各模块的 {@code src/main/java} 下的全部 java 文件（不进 {@code target/}、不进测试源码）。 */
    private static List<File> mainJavaSources(File root) {
        List<File> out = new ArrayList<>();
        File[] modules = root.listFiles();
        assertNotNull(modules, "聚合 pom 所在目录列不出来：" + root);
        for (File module : modules) {
            File src = new File(module, "src/main/java");
            if (module.isDirectory() && src.isDirectory()) {
                collectJava(src, out);
            }
        }
        return out;
    }

    private static void collectJava(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collectJava(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    /**
     * 这个文件在<em>代码</em>里用到了哪些外来日志家族。
     * <p>
     * 不能只看 {@code import}：全限定名 {@code java.util.logging.Logger.getLogger(..)} 不带 import 也一样能用，
     * 只按 import 找会漏。所以这里扫"代码行里出现的包名"，并把注释剥掉。
     * 剥注释是逐行的近似（行首 {@code //}、{@code *}、{@code /*} 整行不算；行尾 {@code //} 只在它不在字符串里时截断），
     * 近似偏<em>严</em>：漏报的方向被压住了，代价是将来一句行尾注释写着外来包名会绊出一条红 —— 那一条红看得见、
     * 改得动，比"统一"这句话悄悄不成立要好。
     */
    private static List<String> loggingPackagesUsedIn(File f) throws Exception {
        List<String> found = new ArrayList<>();
        for (String raw : codeLines(read(f))) {
            for (String pkg : FOREIGN_LOGGING_PACKAGES) {
                if (raw.contains(pkg + ".") && !found.contains(pkg)) {
                    found.add(pkg);
                }
            }
        }
        return found;
    }

    private static boolean usesLog4j2(File f) throws Exception {
        for (String raw : codeLines(read(f))) {
            if (raw.contains("org.apache.logging.log4j.")) {
                return true;
            }
        }
        return false;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** 剥掉注释后的代码行。 */
    private static List<String> codeLines(String text) {
        List<String> out = new ArrayList<>();
        boolean inBlockComment = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            int slash = indexOfCommentStart(line);
            out.add(slash < 0 ? line : line.substring(0, slash));
        }
        return out;
    }

    /** 行内 {@code //} 的位置；引号内的 {@code //}（如 URL）不算注释起点。 */
    private static int indexOfCommentStart(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '/' && line.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void eventsActuallyReachAnAppenderThroughTheLog4j2Implementation() {
        Object raw = LogManager.getLogger(LoggingFamilyGuardTest.class);
        assertEquals("org.apache.logging.log4j.core.Logger", raw.getClass().getName(),
                   "拿到的不是 log4j2 的实现（" + raw.getClass().getName()
                           + "）⇒ API 在裸跑：没有 log4j-core 时它会降级成 SimpleLogger 并静默丢日志");

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        org.apache.logging.log4j.core.config.LoggerConfig root = cfg.getRootLogger();
        Level before = root.getLevel();
        Recorder recorder = new Recorder("logging-family-guard");
        recorder.start();
        cfg.addAppender(recorder);
        // 2.17.2 的真实签名是三个参数（appender, appender-ref 级别, 过滤器），少一个都编译不过。
        root.addAppender(recorder, Level.INFO, null);
        // 没有配置文件时 log4j2 的默认根级别是 ERROR —— 不抬到 INFO 就"事件被级别闸掉"，
        // 那条红会被读成"实现没接上"，而真相是这条判据自己在测错的东西。
        root.setLevel(Level.INFO);
        try {
            LogManager.getLogger("z-cache.logging.family.guard").info("payload-{}", 7);
        } finally {
            root.removeAppender(recorder.getName());
            root.setLevel(before);
            recorder.stop();
        }
        assertTrue(recorder.seen.contains("z-cache.logging.family.guard|INFO|payload-7"),
                   "事件没有走 log4j2 的实现落到 appender（收到的是 " + recorder.seen + "）");
    }

    /** 只看有没有<em>真被调用</em>，不看内容排版：这一支要钉的是"链路通"。 */
    private static final class Recorder extends AbstractAppender {
        private final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();

        Recorder(String name) {
            super(name, null, null, true, new Property[0]);
        }

        @Override
        public void append(LogEvent event) {
            seen.add(event.getLoggerName() + "|" + event.getLevel() + "|"
                     + event.getMessage().getFormattedMessage());
        }
    }
}
