package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.RespArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 事务管理器，实现 MULTI/EXEC/DISCARD/WATCH/UNWATCH 命令。
 * <p>
 * 每个连接维护独立的 {@link TransactionContext}，支持：
 * <ul>
 *   <li>MULTI — 开启事务</li>
 *   <li>EXEC — 执行事务并返回所有命令的结果数组</li>
 *   <li>DISCARD — 清空命令队列并退出事务</li>
 *   <li>WATCH — 监控指定 key，事务执行前校验版本号</li>
 *   <li>UNWATCH — 取消所有监控</li>
 * </ul>
 *
 * @author zifang
 * @since 1.0.2
 */
public class TransactionManager {

    private static final Logger logger = LogManager.getLogger(TransactionManager.class);

    /**
     * 事务上下文，每个连接持有独立实例。
     */
    public static class TransactionContext {

        /** 是否处于事务中（已调用 MULTI） */
        private boolean inTransaction;

        /** 待执行的命令队列 */
        private final List<Object[]> commands = new ArrayList<>();

        /** WATCH 监控的 key 集合 */
        private final Set<String> watchedKeys = new HashSet<>();

        /** WATCH 时记录的 key 版本号快照 */
        private final Map<String, Long> watchedKeyVersions = new HashMap<>();

        /**
         * 是否处于事务中。
         *
         * @return true 表示已调用 MULTI 且尚未 EXEC/DISCARD
         */
        public boolean isInTransaction() {
            return inTransaction;
        }

        /** 设置事务状态（仅供 EXEC 内部使用） */
        public void setInTransaction(boolean inTransaction) {
            this.inTransaction = inTransaction;
        }

        /**
         * 获取待执行命令队列的只读视图。
         *
         * @return 命令队列
         */
        public List<Object[]> getCommands() {
            return commands;
        }

        /**
         * 获取 WATCH 的 key 版本号快照。
         *
         * @return key -> 版本号映射
         */
        public Map<String, Long> getWatchedKeyVersions() {
            return watchedKeyVersions;
        }
    }

    /**
     * 开启事务。
     *
     * @param ctx 事务上下文
     * @throws IllegalStateException 如果已在事务中
     */
    public void multi(TransactionContext ctx) {
        if (ctx.inTransaction) {
            throw new IllegalStateException("ERR MULTI already started");
        }
        ctx.inTransaction = true;
        ctx.commands.clear();
        logger.debug("Transaction started");
    }

    /**
     * 执行事务，按顺序执行所有已排队的命令并返回结果数组。
     * <p>
     * 执行前会检查所有 WATCH key 的版本号是否发生变化：
     * 若任一 key 版本号不一致，则整个事务被中止，返回 null。
     *
     * @param ctx      事务上下文
     * @param executor 实际执行单条命令的回调，入参为命令参数数组
     * @return 所有命令的结果数组，事务中止时返回 null
     */
    public Object exec(TransactionContext ctx, Function<Object[], Object> executor) {
        if (!ctx.inTransaction) {
            throw new IllegalStateException("ERR no transaction in progress");
        }

        // 检查 WATCH 版本号是否发生变化
        if (!ctx.watchedKeys.isEmpty()) {
            for (Map.Entry<String, Long> entry : ctx.watchedKeyVersions.entrySet()) {
                String key = entry.getKey();
                Long oldVersion = entry.getValue();
                // 若当前版本号与记录不一致，说明 key 已被修改
                if (!oldVersion.equals(getCurrentVersion(key))) {
                    logger.debug("Transaction aborted: watched key '{}' was modified", key);
                    resetContext(ctx);
                    return null;
                }
            }
        }

        // 按顺序执行所有命令（先复制列表，避免执行过程中新命令入队导致并发修改）
        List<Object[]> commandsCopy = new ArrayList<>(ctx.commands);
        List<Object> results = new ArrayList<>(commandsCopy.size());
        for (Object[] command : commandsCopy) {
            try {
                Object result = executor.apply(command);
                results.add(result);
            } catch (Exception e) {
                logger.error("Error executing command in transaction: {}", e.getMessage(), e);
                results.add(e);
            }
        }

        resetContext(ctx);
        logger.debug("Transaction executed with {} commands", results.size());
        return RespArray.of(results);
    }

    /**
     * 清空命令队列并退出事务。
     *
     * @param ctx 事务上下文
     */
    public void discard(TransactionContext ctx) {
        if (!ctx.inTransaction) {
            throw new IllegalStateException("ERR no transaction in progress");
        }
        resetContext(ctx);
        logger.debug("Transaction discarded");
    }

    /**
     * WATCH 监控指定的 key，记录当前版本号快照。
     *
     * @param ctx             事务上下文
     * @param keys            要监控的 key 数组
     * @param versionProvider 获取 key 当前版本号的回调
     * @throws IllegalStateException 如果已在事务中
     */
    public void watch(TransactionContext ctx, String[] keys, Function<String, Long> versionProvider) {
        if (ctx.inTransaction) {
            throw new IllegalStateException("ERR WATCH inside MULTI is not allowed");
        }
        for (String key : keys) {
            ctx.watchedKeys.add(key);
            ctx.watchedKeyVersions.put(key, versionProvider.apply(key));
        }
        logger.debug("Watching keys: {}", (Object) keys);
    }

    /**
     * 取消所有 WATCH 监控。
     *
     * @param ctx 事务上下文
     */
    public void unwatch(TransactionContext ctx) {
        ctx.watchedKeys.clear();
        ctx.watchedKeyVersions.clear();
        logger.debug("All watches cleared");
    }

    /**
     * 将命令添加到事务队列中。
     *
     * @param ctx     事务上下文
     * @param command 命令参数数组（如 ["SET", "key", "value"]）
     * @throws IllegalStateException 如果不在事务中
     */
    public void addCommand(TransactionContext ctx, Object[] command) {
        if (!ctx.inTransaction) {
            throw new IllegalStateException("ERR no transaction in progress");
        }
        ctx.commands.add(command);
    }

    /**
     * 检查是否在事务中。
     *
     * @param ctx 事务上下文
     * @return true 表示已调用 MULTI 且尚未 EXEC/DISCARD
     */
    public boolean isInTransaction(TransactionContext ctx) {
        return ctx != null && ctx.inTransaction;
    }

    /**
     * 连接关闭时清理事务上下文，释放所有资源。
     *
     * @param ctx 事务上下文
     */
    public void cleanup(TransactionContext ctx) {
        if (ctx == null) {
            return;
        }
        resetContext(ctx);
        ctx.watchedKeys.clear();
        ctx.watchedKeyVersions.clear();
        logger.debug("Transaction context cleaned up");
    }

    /**
     * 重置事务上下文为初始状态（保留 WATCH 信息）。
     */
    private void resetContext(TransactionContext ctx) {
        ctx.inTransaction = false;
        ctx.commands.clear();
    }

    /**
     * 获取 key 的当前版本号。子类或外部可通过覆盖此方法提供实际版本号逻辑。
     * 默认实现返回 0，表示不做版本校验。
     *
     * @param key 键
     * @return 当前版本号
     */
    protected long getCurrentVersion(String key) {
        return 0L;
    }
}
