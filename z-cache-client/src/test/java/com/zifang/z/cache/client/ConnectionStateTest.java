package com.zifang.z.cache.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectionState 测试类
 * <p>
 * ConnectionState 枚举当前包含 7 个值:
 * DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATING, AUTHENTICATED, CLOSED, ERROR
 */
class ConnectionStateTest {

    @Test
    void testEnumValues() {
        ConnectionState[] states = ConnectionState.values();
        assertEquals(7, states.length);
        assertArrayEquals(new ConnectionState[]{
                ConnectionState.DISCONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.AUTHENTICATING,
                ConnectionState.AUTHENTICATED,
                ConnectionState.CLOSED,
                ConnectionState.ERROR
        }, states);
    }

    @Test
    void testEnumValueOf() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.valueOf("DISCONNECTED"));
        assertEquals(ConnectionState.CONNECTING, ConnectionState.valueOf("CONNECTING"));
        assertEquals(ConnectionState.CONNECTED, ConnectionState.valueOf("CONNECTED"));
        assertEquals(ConnectionState.AUTHENTICATING, ConnectionState.valueOf("AUTHENTICATING"));
        assertEquals(ConnectionState.AUTHENTICATED, ConnectionState.valueOf("AUTHENTICATED"));
        assertEquals(ConnectionState.CLOSED, ConnectionState.valueOf("CLOSED"));
        assertEquals(ConnectionState.ERROR, ConnectionState.valueOf("ERROR"));
    }

    @Test
    void testEnumValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionState.valueOf("UNKNOWN"));
        assertThrows(NullPointerException.class, () -> ConnectionState.valueOf(null));
    }

    @Test
    void testStateNotNull() {
        // 验证状态值本身的逻辑,不包含业务逻辑
        assertNotNull(ConnectionState.DISCONNECTED);
        assertNotNull(ConnectionState.CONNECTING);
        assertNotNull(ConnectionState.CONNECTED);
        assertNotNull(ConnectionState.AUTHENTICATING);
        assertNotNull(ConnectionState.AUTHENTICATED);
        assertNotNull(ConnectionState.CLOSED);
        assertNotNull(ConnectionState.ERROR);
    }

    @Test
    void testOrdinal() {
        // 验证枚举顺序(顺序由源代码定义,新增枚举值需要相应更新)
        assertEquals(0, ConnectionState.DISCONNECTED.ordinal());
        assertEquals(1, ConnectionState.CONNECTING.ordinal());
        assertEquals(2, ConnectionState.CONNECTED.ordinal());
        assertEquals(3, ConnectionState.AUTHENTICATING.ordinal());
        assertEquals(4, ConnectionState.AUTHENTICATED.ordinal());
        assertEquals(5, ConnectionState.CLOSED.ordinal());
        assertEquals(6, ConnectionState.ERROR.ordinal());
    }

    @Test
    void testName() {
        assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.name());
        assertEquals("CONNECTING", ConnectionState.CONNECTING.name());
        assertEquals("CONNECTED", ConnectionState.CONNECTED.name());
        assertEquals("AUTHENTICATING", ConnectionState.AUTHENTICATING.name());
        assertEquals("AUTHENTICATED", ConnectionState.AUTHENTICATED.name());
        assertEquals("CLOSED", ConnectionState.CLOSED.name());
        assertEquals("ERROR", ConnectionState.ERROR.name());
    }

    @Test
    void testCompareTo() {
        // 验证相对顺序
        assertTrue(ConnectionState.DISCONNECTED.compareTo(ConnectionState.CONNECTING) < 0);
        assertTrue(ConnectionState.CONNECTING.compareTo(ConnectionState.CONNECTED) < 0);
        assertTrue(ConnectionState.CONNECTED.compareTo(ConnectionState.AUTHENTICATING) < 0);
        assertTrue(ConnectionState.AUTHENTICATING.compareTo(ConnectionState.AUTHENTICATED) < 0);
        assertTrue(ConnectionState.AUTHENTICATED.compareTo(ConnectionState.CLOSED) < 0);
        assertTrue(ConnectionState.CLOSED.compareTo(ConnectionState.ERROR) < 0);

        // 相等性
        assertEquals(0, ConnectionState.CONNECTED.compareTo(ConnectionState.CONNECTED));
        assertEquals(0, ConnectionState.AUTHENTICATED.compareTo(ConnectionState.AUTHENTICATED));
    }

    @Test
    void testCompareToReverse() {
        // 反向比较应该返回正值
        assertTrue(ConnectionState.CONNECTING.compareTo(ConnectionState.DISCONNECTED) > 0);
        assertTrue(ConnectionState.ERROR.compareTo(ConnectionState.CLOSED) > 0);
    }

    @Test
    void testToString() {
        // 枚举的 toString 默认返回 name
        assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.toString());
        assertEquals("CONNECTING", ConnectionState.CONNECTING.toString());
        assertEquals("CONNECTED", ConnectionState.CONNECTED.toString());
        assertEquals("AUTHENTICATING", ConnectionState.AUTHENTICATING.toString());
        assertEquals("AUTHENTICATED", ConnectionState.AUTHENTICATED.toString());
        assertEquals("CLOSED", ConnectionState.CLOSED.toString());
        assertEquals("ERROR", ConnectionState.ERROR.toString());
    }

    @Test
    void testValuesContentEquality() {
        // values() 内容应保持一致,但允许每次返回新数组副本
        ConnectionState[] first = ConnectionState.values();
        ConnectionState[] second = ConnectionState.values();
        assertArrayEquals(first, second, "values() should return equal arrays");
        // 内容应一致
        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertSame(first[i], second[i], "values()[" + i + "] should be same instance");
        }
    }

    @Test
    void testTransitionLifecycle() {
        // 典型状态转换:DISCONNECTED -> CONNECTING -> CONNECTED -> CLOSED
        ConnectionState state = ConnectionState.DISCONNECTED;
        assertEquals(ConnectionState.DISCONNECTED, state);

        state = ConnectionState.CONNECTING;
        assertEquals(ConnectionState.CONNECTING, state);

        state = ConnectionState.CONNECTED;
        assertEquals(ConnectionState.CONNECTED, state);

        state = ConnectionState.CLOSED;
        assertEquals(ConnectionState.CLOSED, state);
    }

    @Test
    void testTransitionWithAuth() {
        // 带认证的状态转换:DISCONNECTED -> CONNECTING -> CONNECTED -> AUTHENTICATING -> AUTHENTICATED -> CLOSED
        ConnectionState state = ConnectionState.DISCONNECTED;
        state = ConnectionState.CONNECTING;
        state = ConnectionState.CONNECTED;
        state = ConnectionState.AUTHENTICATING;
        assertEquals(ConnectionState.AUTHENTICATING, state);
        state = ConnectionState.AUTHENTICATED;
        assertEquals(ConnectionState.AUTHENTICATED, state);
        state = ConnectionState.CLOSED;
        assertEquals(ConnectionState.CLOSED, state);
    }

    @Test
    void testErrorState() {
        // 错误状态可用于表示失败
        assertEquals(ConnectionState.ERROR, ConnectionState.ERROR);
        assertNotEquals(ConnectionState.CONNECTED, ConnectionState.ERROR);
    }
}