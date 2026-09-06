package com.zifang.z.cache.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectionState 测试类
 */
class ConnectionStateTest {

    @Test
    void testEnumValues() {
        ConnectionState[] states = ConnectionState.values();
        assertEquals(4, states.length);
        assertArrayEquals(new ConnectionState[]{
                ConnectionState.DISCONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.CLOSED
        }, states);
    }

    @Test
    void testEnumValueOf() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.valueOf("DISCONNECTED"));
        assertEquals(ConnectionState.CONNECTING, ConnectionState.valueOf("CONNECTING"));
        assertEquals(ConnectionState.CONNECTED, ConnectionState.valueOf("CONNECTED"));
        assertEquals(ConnectionState.CLOSED, ConnectionState.valueOf("CLOSED"));
    }

    @Test
    void testStateTransitions() {
        // 验证状态值本身的逻辑，不包含业务逻辑
        assertNotNull(ConnectionState.DISCONNECTED);
        assertNotNull(ConnectionState.CONNECTING);
        assertNotNull(ConnectionState.CONNECTED);
        assertNotNull(ConnectionState.CLOSED);
    }

    @Test
    void testOrdinal() {
        // 验证枚举顺序
        assertEquals(0, ConnectionState.DISCONNECTED.ordinal());
        assertEquals(1, ConnectionState.CONNECTING.ordinal());
        assertEquals(2, ConnectionState.CONNECTED.ordinal());
        assertEquals(3, ConnectionState.CLOSED.ordinal());
    }

    @Test
    void testName() {
        assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.name());
        assertEquals("CONNECTING", ConnectionState.CONNECTING.name());
        assertEquals("CONNECTED", ConnectionState.CONNECTED.name());
        assertEquals("CLOSED", ConnectionState.CLOSED.name());
    }

    @Test
    void testCompareTo() {
        // DISCONNECTED < CONNECTING < CONNECTED < CLOSED
        assertTrue(ConnectionState.DISCONNECTED.compareTo(ConnectionState.CONNECTING) < 0);
        assertTrue(ConnectionState.CONNECTING.compareTo(ConnectionState.CONNECTED) < 0);
        assertTrue(ConnectionState.CONNECTED.compareTo(ConnectionState.CLOSED) < 0);
        assertEquals(0, ConnectionState.CONNECTED.compareTo(ConnectionState.CONNECTED));
    }

    @Test
    void testToString() {
        // 枚举的 toString 默认返回 name
        assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.toString());
        assertEquals("CONNECTING", ConnectionState.CONNECTING.toString());
        assertEquals("CONNECTED", ConnectionState.CONNECTED.toString());
        assertEquals("CLOSED", ConnectionState.CLOSED.toString());
    }
}
