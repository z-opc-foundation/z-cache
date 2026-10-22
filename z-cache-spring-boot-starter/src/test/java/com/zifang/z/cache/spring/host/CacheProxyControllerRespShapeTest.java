package com.zifang.z.cache.spring.host;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.core.server.RedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路 B 的逐行读数**必须**是诚实的 —— TASK-20260925-041 的回归锁。
 *
 * <p>这把锁守的是四件事（每一条都对应本卡的一条验收）：
 * <ol>
 *   <li><b>D2</b>：响应体里**没有任何一行的 type 是 dict**（原始 RESP bean 不许漏回前端）；</li>
 *   <li><b>D2</b>：type 的取值域是大写枚举名，与路 A 同一套；</li>
 *   <li><b>D3</b>：应答错位（TYPE 槽里回来 TTL 的整数）**必须**被标成
 *       {@code desyncSuspect=true} + {@code type=null} + {@code rawReply}，
 *       <b>不许</b>被洗成一个看起来正常的类型；</li>
 *   <li><b>D4</b>：路 B 的 {@code byteLength} 恒为 null（那一列**没测**），
 *       不许拿 -1 冒充"非 string"。</li>
 * </ol>
 *
 * <p>另加一条 <b>D1</b>：路 A 不可用时 503 的 hint 必须是**真因**（B1 / 没 bind），
 * 不能再出现那句"反射为空"（反射明明是成功的）。
 *
 * <p>路 A 的现场用"反射得到一个**没 bind** 的 RedisServer"来模拟（= B1），
 * 错位现场用 {@link CacheProxyControllerLocalHarness.MisbehavingRespServer}（故意把
 * TTL 的整数答到 TYPE 槽里）—— 真实环境里那 6~7% 是间歇的，靠运气复现不了，
 * 这里让它 100% 发生，判据才是可测的。
 */
class CacheProxyControllerRespShapeTest {

    private static final Set<String> VALID_TYPES = new HashSet<String>(
            Arrays.asList("STRING", "HASH", "LIST", "SET", "ZSET", "NONE"));

    private Object originalSharedServer;
    private Field sharedServerField;

    @BeforeEach
    void setUp() throws Exception {
        sharedServerField = ZCacheEmbeddedServerConfig.class.getDeclaredField("SHARED_SERVER");
        sharedServerField.setAccessible(true);
        originalSharedServer = sharedServerField.get(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        sharedServerField.set(null, originalSharedServer);
    }

    // ------------------------------------------------------------------ D1

    @Test
    @DisplayName("D1：路 A 不可用时 503 的 hint 说真因（B1/没 bind），不再说\"反射为空\"")
    void embedded503HintNamesTheRealCause() throws Exception {
        CacheProxyController ctl = controllerWithBrokenEmbedded();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ctl.keys("*", 0, 10, "embedded", resp);

        assertEquals(503, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("isRunning()=false"), "hint 里的真因没有点名 isRunning=false：" + body);
        assertTrue(body.contains("B1"), "hint 里的真因没有点名 B1：" + body);
        assertFalse(body.contains("反射为空"), "hint 还在说那句错的归因（反射为空）：" + body);
        // 两个口径必须同页并列，不能互相打脸
        assertTrue(body.contains("\"serverFieldReflected\":true"), body);
        assertTrue(body.contains("\"embeddedReadPathUsable\":false"), body);
    }

    @Test
    @DisplayName("D1：/hotkeys 的 503 也要说真因（它是同一句撒谎表达式的第二个受害者）")
    void hotkeys503HintNamesTheRealCause() throws Exception {
        CacheProxyController ctl = controllerWithBrokenEmbedded();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ctl.hotkeys(0, 10, resp);

        assertEquals(503, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("B1"), "hotkeys 的 hint 没有点名 B1：" + body);
        assertFalse(body.contains("反射为空"), "hotkeys 的 hint 还在说\"反射为空\"：" + body);
    }

    // ------------------------------------------------------------------ D2 / D4

    @Test
    @DisplayName("D2/D4：type 不是 dict、取值域是大写枚举名；byteLength 恒 null（没测）")
    void clientPathTypeIsNormalizedAndByteLengthIsNull() throws Exception {
        RedisServer real = startRealServer();
        try {
            ZCacheClient client = new ZCacheClient("127.0.0.1", real.getPort());
            client.set("t_key1", "v1");
            client.set("t_key2", "v2");
            CacheProxyController ctl = controller(client);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            ctl.keys("*", 0, 50, "client", resp);
            assertEquals(200, resp.getStatus());
            String body = resp.getContentAsString();

            List<Map<String, Object>> rows = rowsOf(body);
            assertFalse(rows.isEmpty(), "没取到行 ⇒ 本条没测到，不算通过");
            for (Map<String, Object> row : rows) {
                Object type = row.get("type");
                assertFalse(type instanceof Map, "有一行的 type 是 dict（原始 RESP bean 泄漏）：" + row);
                assertTrue(type instanceof String, "有一行的 type 不是字符串：" + row);
                assertTrue(VALID_TYPES.contains(type), "type 不在合法取值域里：" + row);
                // D4：路 B 不发 STRLEN ⇒ 那一列"没测"，必须是 null，不许是 -1
                assertNull(row.get("byteLength"), "byteLength 不该有值（路 B 未发 STRLEN）：" + row);
                assertNotNull(row.get("byteLengthNote"), "byteLength 缺 note（读者不知道这是没测）：" + row);
                // D4：行内 authority 要有真实来源
                assertNotNull(row.get("authority"), "行内 authority 缺失：" + row);
            }
        } finally {
            real.stop();
        }
    }

    // ------------------------------------------------------------------ D3

    @Test
    @DisplayName("D3：应答错位必须被标记并降级（desyncSuspect + type=null + rawReply），不许洗成正常类型")
    void desyncRowsAreMarkedAndDegraded() throws Exception {
        CacheProxyControllerLocalHarness.MisbehavingRespServer bad =
                new CacheProxyControllerLocalHarness.MisbehavingRespServer(
                        CacheProxyControllerLocalHarness.freePort());
        bad.start();
        ZCacheClient client = new ZCacheClient("127.0.0.1", bad.port());
        CacheProxyController ctl = controller(client);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        ctl.keys("*", 0, 50, "client", resp);
        assertEquals(200, resp.getStatus());
        String body = resp.getContentAsString();

        List<Map<String, Object>> rows = rowsOf(body);
        assertFalse(rows.isEmpty(), "没取到行 ⇒ 本条没测到，不算通过");
        int desync = 0;
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("desyncSuspect"))) {
                desync++;
                assertNull(row.get("type"), "错位行不许显示成类型：" + row);
                assertNotNull(row.get("rawReply"), "错位行必须留原始应答：" + row);
                assertNotNull(row.get("desyncReason"), "错位行必须说清为什么不信：" + row);
            } else {
                assertFalse(row.get("type") instanceof Map, "type 是 dict：" + row);
            }
        }
        assertEquals(rows.size(), desync, "这台 server 100% 错位 ⇒ 每一行都该被标出来");
        // 顶层的两个数必须与逐行数出来的对得上
        Map<String, Object> root = com.zifang.util.json.JsonUtil.fromJson(body,
                new com.zifang.util.json.define.TypeReference<Map<String, Object>>() {
                });
        assertEquals(desync, ((Number) root.get("desyncRows")).intValue(), "顶层 desyncRows 与逐行数出来的对不上");
        assertEquals(rows.size(), ((Number) root.get("totalRows")).intValue(), "顶层 totalRows 与行数对不上");
    }

    // ------------------------------------------------------------------ 工具

    private CacheProxyController controllerWithBrokenEmbedded() throws Exception {
        // 反射成功、但没 bind（B1 现场）
        CacheProxyControllerLocalHarness.setSharedServer(
                new RedisServer("127.0.0.1", CacheProxyControllerLocalHarness.freePort(), 64));
        return new CacheProxyController(provider(null), provider((ZCacheEmbeddedServerConfig) null));
    }

    private CacheProxyController controller(ZCacheClient client) {
        return new CacheProxyController(provider(client), provider((ZCacheEmbeddedServerConfig) null));
    }

    private static RedisServer startRealServer() throws Exception {
        final RedisServer server = new RedisServer("127.0.0.1",
                CacheProxyControllerLocalHarness.freePort(), 1024);
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable ignore) {
                // 停服时正常抛出
            }
        });
        t.setDaemon(true);
        t.start();
        CacheProxyControllerLocalHarness.waitUntilRunning(server);
        return server;
    }

    /** 取响应体里的 keys 数组（用仓里自己的 JsonUtil 解析，不手搓 JSON） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(String json) {
        Map<String, Object> root = com.zifang.util.json.JsonUtil.fromJson(json,
                new com.zifang.util.json.define.TypeReference<Map<String, Object>>() {
                });
        Object keys = root.get("keys");
        if (!(keys instanceof List)) {
            return new java.util.ArrayList<Map<String, Object>>();
        }
        return (List<Map<String, Object>>) keys;
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(final T value) {
        return new org.springframework.beans.factory.ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(Object.class);
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                java.util.List<T> l = new java.util.ArrayList<T>();
                if (value != null) {
                    l.add(value);
                }
                return l.iterator();
            }
        };
    }
}
