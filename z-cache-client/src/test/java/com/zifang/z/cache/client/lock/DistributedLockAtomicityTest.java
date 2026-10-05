package com.zifang.z.cache.client.lock;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.common.protocol.RespBulkString;
import com.zifang.z.cache.common.protocol.RespSimpleString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁的<b>原子性现状</b>：把「ownerId 校验保证安全性」这句话实测一遍。
 *
 * <p>背景：<b>当前 z-cache-server 没有实现 EVAL/SCRIPT</b> —— 全仓 grep {@code EVAL}
 * 只命中 {@link DistributedLockImpl} 与它的单测，{@code resources/scripts/*.lua} 是
 * 客户端侧资源，服务端没有解释器。所以 {@code tryUnlockViaEval} /
 * {@code tryRenewViaEval} 对着自家服务端<b>每次都落回</b> GET + 条件 DEL/SET 的
 * 非原子路径。它们不是「以防万一」的兜底，是唯一路径。</p>
 *
 * <p>而 ownerId 校验与 DEL/SET 分属两次独立往返，校验通过之后到删除/改写到达服务端
 * 之间，锁可能因 TTL 到期消失并被别人拿走。本类把这条窗口实测出来，并钉住
 * 「对着自家服务端走的就是这条路径」，避免后来者按「原子」来用这把锁。</p>
 *
 * <p><b>注意</b>：下面两条以 {@code knownRace_} 开头的用例断言的是<b>当前的缺陷行为</b>，
 * 不是期望行为。它们的作用是把窗口钉在测试里 —— 哪天服务端补上原子的
 * compare-and-delete，这两条会失败，那正是好信号（把断言改成「不再删别人的锁」即可）。
 * 真正的根治需要服务端新增一条原子命令，不在客户端能单独解决的范围内。
 */
class DistributedLockAtomicityTest {

    private ZCacheClient mockClient;
    private DistributedLock lockClient;

    /** 记录真正发出去过的命令序列（第一段 = 命令名）。 */
    private final List<String> issued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockClient = mock(ZCacheClient.class);
        lockClient = new DistributedLockImpl(mockClient);
    }

    @AfterEach
    void tearDown() {
        lockClient.close();
    }

    /** 服务端不认识 EVAL —— 与当前 z-cache-server 的真实行为一致。 */
    private void serverHasNoEval() {
        when(mockClient.sendCommand(eq("EVAL"), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("ERR unknown command 'EVAL'"));
    }

    private Lock acquire(String key, long ttlMs) {
        when(mockClient.sendCommand(eq("SET"), eq(key), anyString(),
                eq("NX"), eq("PX"), eq(String.valueOf(ttlMs))))
                .thenReturn(RespSimpleString.of("OK"));
        Lock lock = lockClient.tryLock(key, ttlMs);
        assertTrue(lock != null, "应能拿到锁");
        return lock;
    }

    @Test
    @DisplayName("对着没有 EVAL 的服务端，unlock 走的是 GET/GET/DEL 三次往返（不是原子路径）")
    void unlockAgainstRealServerUsesTheNonAtomicPath() throws Exception {
        serverHasNoEval();
        Lock lock = acquire("k1", 30_000);

        AtomicInteger gets = new AtomicInteger();
        when(mockClient.sendCommand(eq("GET"), eq("k1"))).thenAnswer(inv -> {
            gets.incrementAndGet();
            return RespBulkString.of(lock.getOwnerId() + "|" + lock.getFencingToken());
        });

        issued.clear();
        lockClient.unlock(lock);

        // 断言的是「这条路径确实被走到」：两次 owner 校验 + 一次删除
        assertEquals(2, gets.get(), "unlockFallback 会 GET 两次（第二次被注释称作「双重检查」）");
        verify(mockClient).sendCommand(eq("DEL"), eq("k1"));
    }

    @Test
    @DisplayName("对着没有 EVAL 的服务端，renew 走的是 GET + 无护栏的 SET（连 XX 都没有）")
    void renewAgainstRealServerUsesTheNonAtomicPath() {
        serverHasNoEval();
        Lock lock = acquire("k2", 10_000);

        String value = lock.getOwnerId() + "|" + lock.getFencingToken();
        when(mockClient.sendCommand(eq("GET"), eq("k2"))).thenReturn(RespBulkString.of(value));
        when(mockClient.sendCommand(eq("SET"), eq("k2"), eq(value), eq("PX"), eq("30000")))
                .thenReturn(RespSimpleString.of("OK"));

        assertTrue(lockClient.renew(lock, 30_000), "owner 匹配时续约应成功");

        // 关键：这条 SET 没有任何存在性/归属护栏 —— 没有 XX，也没有 owner 复核
        verify(mockClient, never()).sendCommand(eq("SET"), eq("k2"), eq(value),
                eq("XX"), any(), any());
    }

    @Test
    @DisplayName("第二次 GET 发现已易主时不得发 DEL（这条守住 unlockFallback 里那道复核）")
    void ownerChangedBeforeTheSecondCheck_MustNotDelete() throws Exception {
        serverHasNoEval();
        Lock lock = acquire("k5", 30_000);
        String mine = lock.getOwnerId() + "|" + lock.getFencingToken();
        final String theirs = "other-owner|999";

        // 第一次 GET 通过校验；第二次 GET 已经是别人的了 → 必须停在复核这一步
        AtomicInteger gets = new AtomicInteger();
        when(mockClient.sendCommand(eq("GET"), eq("k5"))).thenAnswer(inv ->
                RespBulkString.of(gets.incrementAndGet() == 1 ? mine : theirs));

        lockClient.unlock(lock);

        assertEquals(2, gets.get(), "两次 owner 校验都应发生");
        verify(mockClient, never()).sendCommand(eq("DEL"), eq("k5"));
    }

    @Test
    @DisplayName("owner 不匹配时不得发 SET（这条守住 renewFallback 里的归属校验）")
    void renewWithForeignOwner_MustNotWrite() {
        serverHasNoEval();
        Lock lock = acquire("k6", 10_000);

        when(mockClient.sendCommand(eq("GET"), eq("k6")))
                .thenReturn(RespBulkString.of("other-owner|999"));

        assertEquals(false, lockClient.renew(lock, 30_000), "不是自己的锁，续约必须失败");
        verify(mockClient, never()).sendCommand(eq("SET"), eq("k6"), any(), any(), any());
    }

    @Test
    @DisplayName("已知限制：两次校验都通过之后锁易主，那条 DEL 仍然会删掉新主人的锁")
    void knownRace_unlockDeletesTheNewOwnersLock() throws Exception {
        serverHasNoEval();
        Lock lock = acquire("k3", 30_000);
        String mine = lock.getOwnerId() + "|" + lock.getFencingToken();
        final String theirs = "other-owner|999";

        // 第一次 GET：还是自己的（校验通过）
        // 第二次 GET：也还是自己的（校验再次通过）
        // 第三次往返 DEL 之前，锁因 TTL 到期被别人拿走 —— GET 已经过去了，DEL 拦不住
        AtomicInteger gets = new AtomicInteger();
        when(mockClient.sendCommand(eq("GET"), eq("k3"))).thenAnswer(inv ->
                RespBulkString.of(gets.incrementAndGet() <= 2 ? mine : theirs));

        lockClient.unlock(lock);

        verify(mockClient).sendCommand(eq("DEL"), eq("k3"));
        // 这就是缺陷本身：ownerId 校验在两次往返里都通过了，DEL 依旧照发。
        // 业务侧唯一能兜住的是 fencing token（DB 写入处 fencing_token < ? 拒绝陈旧持有者）。
        System.out.println("[已知限制] ownerId 校验通过后 DEL 仍发出，对端此刻已是 " + theirs);
    }

    @Test
    @DisplayName("已知限制：校验通过后锁易主，renew 的 SET 会把别人的锁值覆盖成自己的旧值")
    void knownRace_renewOverwritesTheNewOwnersLock() {
        serverHasNoEval();
        Lock lock = acquire("k4", 10_000);
        String mine = lock.getOwnerId() + "|" + lock.getFencingToken();
        final String theirs = "other-owner|999";

        // GET 返回自己（校验通过）→ 随后锁被别人拿走 → SET 照发，把值覆盖回去
        when(mockClient.sendCommand(eq("GET"), eq("k4"))).thenReturn(RespBulkString.of(mine));
        when(mockClient.sendCommand(eq("SET"), eq("k4"), eq(mine), eq("PX"), eq("30000")))
                .thenReturn(RespSimpleString.of("OK"));

        assertTrue(lockClient.renew(lock, 30_000), "当前实现会认为续约成功");

        // 被覆盖的正是新主人的锁值，且 TTL 被一并重置
        verify(mockClient).sendCommand(eq("SET"), eq("k4"), eq(mine), eq("PX"), eq("30000"));
        System.out.println("[已知限制] renew 把 " + theirs + " 覆盖回 " + mine + " 并重置 TTL");
    }
}
