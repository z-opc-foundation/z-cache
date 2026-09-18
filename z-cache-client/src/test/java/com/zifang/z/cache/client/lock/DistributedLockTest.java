package com.zifang.z.cache.client.lock;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.common.protocol.RespBulkString;
import com.zifang.z.cache.common.protocol.RespInteger;
import com.zifang.z.cache.common.protocol.RespSimpleString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DistributedLock 单元测试（Mockito 模拟 ZCacheClient）。
 *
 * <p>测试覆盖设计文档 §8.1 的 8 个用例。
 *
 * @author zifang
 * @since 1.3.0
 */
class DistributedLockTest {

    private ZCacheClient mockClient;
    private DistributedLock lockClient;

    @BeforeEach
    void setUp() {
        mockClient = mock(ZCacheClient.class);
        lockClient = new DistributedLockImpl(mockClient);
    }

    @AfterEach
    void tearDown() {
        lockClient.close();
    }

    // ==================== testAcquireLock_Success ====================

    @Test
    void testAcquireLock_Success() {
        // SET key value NX PX ttlMs -> OK
        when(mockClient.sendCommand(eq("SET"), eq("test-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock lock = lockClient.tryLock("test-key", 30_000);

        assertNotNull(lock);
        assertEquals("test-key", lock.getKey());
        assertNotNull(lock.getOwnerId());
        assertTrue(lock.getFencingToken() > 0);
        assertEquals(30_000, lock.getTtlMs());
    }

    // ==================== testAcquireLock_FailOnContention ====================

    @Test
    void testAcquireLock_FailOnContention() {
        // SET 返回 null（key 已存在）
        when(mockClient.sendCommand(eq("SET"), eq("contested-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(null);

        Lock lock = lockClient.tryLock("contested-key", 30_000);

        assertNull(lock);
    }

    // ==================== testUnlock_Success ====================

    @Test
    void testUnlock_Success() throws LockReleaseException {
        String ownerId = "test-owner-uuid";
        long fencingToken = 42L;
        String value = ownerId + "|" + fencingToken;

        // tryLock
        when(mockClient.sendCommand(eq("SET"), eq("unlock-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock lock = lockClient.tryLock("unlock-key", 30_000);
        assertNotNull(lock);

        // unlock -> EVAL 返回 1（成功）
        when(mockClient.sendCommand(eq("EVAL"), any(), eq("1"),
                eq("unlock-key"), any(), any()))
                .thenReturn(RespInteger.of(1));

        assertDoesNotThrow(() -> lockClient.unlock(lock));
    }

    // ==================== testUnlock_FailOnForeignLock ====================

    @Test
    void testUnlock_FailOnForeignLock() {
        // EVAL 不支持（模拟真实场景：服务端不支持 EVAL）
        when(mockClient.sendCommand(eq("EVAL"), any(), any()))
                .thenThrow(new RuntimeException("ERR unknown command"));

        // 先获取锁
        when(mockClient.sendCommand(eq("SET"), eq("foreign-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock myLock = lockClient.tryLock("foreign-key", 30_000);
        assertNotNull(myLock);

        // GET 返回他人持有的锁值
        when(mockClient.sendCommand(eq("GET"), eq("foreign-key")))
                .thenReturn(RespBulkString.of("other-owner|99"));

        LockReleaseException ex = assertThrows(LockReleaseException.class,
                () -> lockClient.unlock(myLock));
        assertTrue(ex.getMessage().contains("another owner"));
    }

    // ==================== testUnlock_Fallback_OwnerMismatch ====================

    @Test
    void testUnlock_Fallback_OwnerMismatch() {
        // EVAL 不支持（抛异常）
        when(mockClient.sendCommand(eq("EVAL"), any(), any()))
                .thenThrow(new RuntimeException("ERR unknown command"));

        // 先获取锁
        when(mockClient.sendCommand(eq("SET"), eq("fb-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock myLock = lockClient.tryLock("fb-key", 30_000);
        assertNotNull(myLock);

        // GET 返回他人持有的值
        when(mockClient.sendCommand(eq("GET"), eq("fb-key")))
                .thenReturn(RespBulkString.of("other-owner|99"));

        LockReleaseException ex = assertThrows(LockReleaseException.class,
                () -> lockClient.unlock(myLock));
        assertTrue(ex.getMessage().contains("another owner"));
    }

    // ==================== testRenew_Success ====================

    @Test
    void testRenew_Success() {
        // tryLock
        when(mockClient.sendCommand(eq("SET"), eq("renew-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock lock = lockClient.tryLock("renew-key", 10_000);
        assertNotNull(lock);

        // renew -> EVAL 返回 1
        when(mockClient.sendCommand(eq("EVAL"), any(), eq("1"),
                eq("renew-key"), any(), any()))
                .thenReturn(RespInteger.of(1));

        boolean result = lockClient.renew(lock, 30_000);
        assertTrue(result);
    }

    // ==================== testRenew_FailOnExpiredLock ====================

    @Test
    void testRenew_FailOnExpiredLock() {
        // tryLock
        when(mockClient.sendCommand(eq("SET"), eq("expired-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock lock = lockClient.tryLock("expired-key", 10_000);
        assertNotNull(lock);

        // renew -> EVAL 返回 0（锁已不存在）
        when(mockClient.sendCommand(eq("EVAL"), any(), eq("1"),
                eq("expired-key"), any(), any()))
                .thenReturn(RespInteger.of(0));

        boolean result = lockClient.renew(lock, 10_000);
        assertFalse(result);
    }

    // ==================== testRenew_Fallback_Success ====================

    @Test
    void testRenew_Fallback_Success() {
        // EVAL 不支持
        when(mockClient.sendCommand(eq("EVAL"), anyString(), any()))
                .thenThrow(new RuntimeException("ERR unknown command"));

        // tryLock
        when(mockClient.sendCommand(eq("SET"), eq("fb-renew-key"), anyString(),
                eq("NX"), eq("PX"), anyString()))
                .thenReturn(RespSimpleString.of("OK"));

        Lock lock = lockClient.tryLock("fb-renew-key", 10_000);
        assertNotNull(lock);

        // GET 返回自己持有的锁
        String ownerId = lock.getOwnerId();
        String value = ownerId + "|" + lock.getFencingToken();
        when(mockClient.sendCommand(eq("GET"), eq("fb-renew-key")))
                .thenReturn(RespBulkString.of(value));

        // SET XX PX 返回 OK
        when(mockClient.sendCommand(eq("SET"), eq("fb-renew-key"), eq(value),
                eq("PX"), eq("30000")))
                .thenReturn(RespSimpleString.of("OK"));

        boolean result = lockClient.renew(lock, 30_000);
        assertTrue(result);
    }

    // ==================== testCurrentOwner ====================

    @Test
    void testCurrentOwner() {
        when(mockClient.sendCommand(eq("GET"), eq("owner-key")))
                .thenReturn(RespBulkString.of("some-owner|123"));

        String owner = lockClient.currentOwner("owner-key");
        assertEquals("some-owner", owner);
    }

    @Test
    void testCurrentOwner_KeyNotExist() {
        when(mockClient.sendCommand(eq("GET"), eq("no-key")))
                .thenReturn(null);

        String owner = lockClient.currentOwner("no-key");
        assertNull(owner);
    }

    // ==================== testArgumentValidation ====================

    @Test
    void testTryLock_NullKey() {
        assertThrows(IllegalArgumentException.class,
                () -> lockClient.tryLock(null, 30_000));
    }

    @Test
    void testTryLock_ZeroTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> lockClient.tryLock("key", 0));
    }

    @Test
    void testTryLock_NegativeTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> lockClient.tryLock("key", -1));
    }

    @Test
    void testUnlock_NullLock() {
        assertThrows(IllegalArgumentException.class,
                () -> lockClient.unlock(null));
    }

    // ==================== testClose ====================

    @Test
    void testClose() {
        DistributedLockImpl impl = new DistributedLockImpl(mockClient);
        assertDoesNotThrow(impl::close);
        // double close should be safe
        assertDoesNotThrow(impl::close);
    }
}
