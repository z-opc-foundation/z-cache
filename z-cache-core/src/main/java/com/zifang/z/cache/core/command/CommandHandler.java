package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis命令处理器
 * 处理RESP数组格式的请求并执行对应的命令
 *
 * @author zifang
 * @since 1.0.0
 */
public class CommandHandler {
    private static final Logger logger = LogManager.getLogger(CommandHandler.class);

    private final MemoryStore store;
    private final String password;
    private volatile boolean authenticated;

    /**
     * 构造函数
     *
     * @param store 内存存储实例
     */
    public CommandHandler(MemoryStore store) {
        this(store, null);
    }

    /**
     * 创建带可选密码认证的命令处理器。密码为空时关闭认证。
     */
    public CommandHandler(MemoryStore store, String password) {
        this.store = store;
        this.password = password;
        this.authenticated = password == null;
    }

    /**
     * 处理RESP请求（必须是数组格式的命令）
     *
     * @param request RESP请求对象
     * @return 执行结果，错误时返回RespError
     */
    public Object handle(Object request) {
        if (request == null) {
            return RespError.of("ERR", "empty request");
        }

        if (!(request instanceof RespArray)) {
            logger.warn("Request is not an array: {}", request.getClass().getName());
            return RespError.of("ERR", "Protocol error: expected array");
        }

        RespArray array = (RespArray) request;
        String[] args = array.toStringArray();

        if (args.length == 0) {
            return RespError.of("ERR", "empty command");
        }

        String cmd = args[0].toUpperCase();
        logger.debug("Processing command: {} with {} args", cmd, args.length);

        if ("AUTH".equals(cmd)) {
            return handleAuth(args);
        }
        if (!authenticated) {
            return RespError.of("NOAUTH", "Authentication required.");
        }

        try {
            switch (cmd) {
                // Connection commands
                case "PING":
                    return handlePing(args);
                case "ECHO":
                    return handleEcho(args);
                case "QUIT":
                    return RespSimpleString.of("OK");
                case "SELECT":
                    return handleSelect(args);

                // String commands
                case "SET":
                    return handleSet(args);
                case "GET":
                    return handleGet(args);
                case "DEL":
                    return handleDel(args);
                case "EXISTS":
                    return handleExists(args);
                case "EXPIRE":
                    return handleExpire(args);
                case "TTL":
                    return handleTtl(args);
                case "PTTL":
                    return handlePttl(args);
                case "PERSIST":
                    return handlePersist(args);
                case "SETEX":
                    return handleSetex(args);
                case "PSETEX":
                    return handlePsetex(args);
                case "SETNX":
                    return handleSetnx(args);
                case "GETSET":
                    return handleGetset(args);
                case "MGET":
                    return handleMget(args);
                case "MSET":
                    return handleMset(args);
                case "APPEND":
                    return handleAppend(args);
                case "STRLEN":
                    return handleStrlen(args);
                case "INCR":
                    return handleIncrement(args, 1);
                case "DECR":
                    return handleIncrement(args, -1);
                case "INCRBY":
                    return handleIncrementBy(args, 1);
                case "DECRBY":
                    return handleIncrementBy(args, -1);
                case "PEXPIRE":
                    return handlePexpire(args);
                case "KEYS":
                    return handleKeys(args);
                case "DBSIZE":
                    return RespInteger.of(store.dbsize());
                case "FLUSHDB":
                    store.flush();
                    return RespSimpleString.of("OK");
                case "FLUSHALL":
                    store.flush();
                    return RespSimpleString.of("OK");
                case "INFO":
                    return handleInfo(args);
                case "TYPE":
                    return handleType(args);

                // Unknown command
                default:
                    logger.warn("Unknown command: {}", cmd);
                    return RespError.unknownCommand(cmd);
            }
        } catch (Exception e) {
            logger.error("Error executing command: {} - {}", cmd, e.getMessage(), e);
            return RespError.of("ERR", "internal error: " + e.getMessage());
        }
    }

    private Object handleAuth(String[] args) {
        if (password == null) {
            return RespError.of("ERR", "AUTH called without any password configured");
        }
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("AUTH");
        }
        if (password.equals(args[1])) {
            authenticated = true;
            return RespSimpleString.of("OK");
        }
        return RespError.of("WRONGPASS", "invalid username-password pair or user is disabled.");
    }

    /**
     * 处理PING命令
     *
     * @param args 命令参数
     * @return PONG响应或带消息的响应
     */
    private Object handlePing(String[] args) {
        if (args.length == 1) {
            return RespSimpleString.of("PONG");
        } else if (args.length == 2) {
            return RespBulkString.of(args[1]);
        } else {
            return RespError.wrongNumberOfArguments("PING");
        }
    }

    /**
     * 处理ECHO命令
     *
     * @param args 命令参数
     * @return 回显消息
     */
    private Object handleEcho(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("ECHO");
        }
        return RespBulkString.of(args[1]);
    }

    /**
     * 处理SELECT命令
     *
     * @param args 命令参数
     * @return OK响应
     */
    private Object handleSelect(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("SELECT");
        }
        // Currently only support database 0
        try {
            int db = Integer.parseInt(args[1]);
            if (db != 0) {
                return RespError.of("ERR", "DB index is out of range");
            }
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "invalid DB index");
        }
        return RespSimpleString.of("OK");
    }

    // ==================== String Commands ====================

    /**
     * 处理SET命令
     *
     * @param args 命令参数
     * @return OK响应或nil
     */
    private Object handleSet(String[] args) {
        if (args.length < 3) {
            return RespError.wrongNumberOfArguments("SET");
        }

        String key = args[1];
        String value = args[2];

        // Parse options
        Integer expireSeconds = null;
        Long expireMillis = null;
        boolean nx = false; // Only set if not exists
        boolean xx = false; // Only set if exists

        for (int i = 3; i < args.length; i++) {
            String opt = args[i].toUpperCase();
            switch (opt) {
                case "EX":
                    if (i + 1 >= args.length) {
                        return RespError.syntaxError();
                    }
                    try {
                        expireSeconds = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        return RespError.of("ERR", "value is not an integer or out of range");
                    }
                    break;
                case "PX":
                    if (i + 1 >= args.length) {
                        return RespError.syntaxError();
                    }
                    try {
                        expireMillis = Long.parseLong(args[++i]);
                    } catch (NumberFormatException e) {
                        return RespError.of("ERR", "value is not an integer or out of range");
                    }
                    break;
                case "NX":
                    nx = true;
                    break;
                case "XX":
                    xx = true;
                    break;
                default:
                    return RespError.syntaxError();
            }
        }

        // Check NX/XX conditions
        boolean exists = store.exists(key);
        if (nx && exists) {
            return RespBulkString.nullBulkString(); // Don't set, return nil
        }
        if (xx && !exists) {
            return RespBulkString.nullBulkString(); // Don't set, return nil
        }

        // Store the value
        if (expireMillis != null) {
            store.psetex(key, expireMillis, value.getBytes(StandardCharsets.UTF_8));
        } else if (expireSeconds != null) {
            store.setex(key, expireSeconds, value.getBytes(StandardCharsets.UTF_8));
        } else {
            store.set(key, value.getBytes(StandardCharsets.UTF_8));
        }

        return RespSimpleString.of("OK");
    }

    /**
     * 处理GET命令
     *
     * @param args 命令参数
     * @return 值或nil
     */
    private Object handleGet(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("GET");
        }
        String key = args[1];
        byte[] value = store.get(key);
        if (value == null) {
            return RespBulkString.nullBulkString();
        }
        return RespBulkString.of(value);
    }

    /**
     * 处理DEL命令
     *
     * @param args 命令参数
     * @return 删除的键数量
     */
    private Object handleDel(String[] args) {
        if (args.length < 2) {
            return RespError.wrongNumberOfArguments("DEL");
        }
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, keys.length);
        long deleted = store.del(keys);
        return RespInteger.of(deleted);
    }

    /**
     * 处理EXISTS命令
     *
     * @param args 命令参数
     * @return 存在的键数量
     */
    private Object handleExists(String[] args) {
        if (args.length < 2) {
            return RespError.wrongNumberOfArguments("EXISTS");
        }
        long count = 0;
        for (int i = 1; i < args.length; i++) {
            if (store.exists(args[i])) {
                count++;
            }
        }
        return RespInteger.of(count);
    }

    /**
     * 处理EXPIRE命令
     *
     * @param args 命令参数
     * @return 1表示设置成功，0表示键不存在
     */
    private Object handleExpire(String[] args) {
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments("EXPIRE");
        }
        String key = args[1];
        int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        }
        boolean result = store.expire(key, seconds);
        return RespInteger.of(result ? 1 : 0);
    }

    /**
     * 处理TTL命令
     *
     * @param args 命令参数
     * @return 剩余过期时间（秒）
     */
    private Object handleTtl(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("TTL");
        }
        String key = args[1];
        long ttl = store.ttl(key);
        return RespInteger.of(ttl);
    }

    /**
     * 处理PERSIST命令
     *
     * @param args 命令参数
     * @return 1表示移除成功，0表示键不存在或无过期时间
     */
    private Object handlePersist(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("PERSIST");
        }
        String key = args[1];
        boolean result = store.persist(key);
        return RespInteger.of(result ? 1 : 0);
    }

    /**
     * 处理SETEX命令
     *
     * @param args 命令参数
     * @return OK响应
     */
    private Object handleSetex(String[] args) {
        if (args.length != 4) {
            return RespError.wrongNumberOfArguments("SETEX");
        }
        String key = args[1];
        int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        }
        byte[] value = args[3].getBytes(StandardCharsets.UTF_8);
        store.setex(key, seconds, value);
        return RespSimpleString.of("OK");
    }

    /**
     * 处理PSETEX命令
     *
     * @param args 命令参数
     * @return OK响应
     */
    private Object handlePsetex(String[] args) {
        if (args.length != 4) {
            return RespError.wrongNumberOfArguments("PSETEX");
        }
        String key = args[1];
        long milliseconds;
        try {
            milliseconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        }
        byte[] value = args[3].getBytes(StandardCharsets.UTF_8);
        store.psetex(key, milliseconds, value);
        return RespSimpleString.of("OK");
    }

    private Object handleSetnx(String[] args) {
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments("SETNX");
        }
        return RespInteger.of(store.setIfAbsent(args[1], args[2].getBytes(StandardCharsets.UTF_8)) ? 1 : 0);
    }

    private Object handleGetset(String[] args) {
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments("GETSET");
        }
        byte[] oldValue = store.getAndSet(args[1], args[2].getBytes(StandardCharsets.UTF_8));
        return oldValue == null ? RespBulkString.nullBulkString() : RespBulkString.of(oldValue);
    }

    private Object handleMget(String[] args) {
        if (args.length < 2) {
            return RespError.wrongNumberOfArguments("MGET");
        }
        List<byte[]> values = store.mget(java.util.Arrays.copyOfRange(args, 1, args.length));
        Object[] response = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) {
            response[i] = values.get(i) == null ? RespBulkString.nullBulkString() : RespBulkString.of(values.get(i));
        }
        return RespArray.of(response);
    }

    private Object handleMset(String[] args) {
        if (args.length < 3 || args.length % 2 == 0) {
            return RespError.wrongNumberOfArguments("MSET");
        }
        for (int i = 1; i < args.length; i += 2) {
            store.set(args[i], args[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return RespSimpleString.of("OK");
    }

    private Object handleAppend(String[] args) {
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments("APPEND");
        }
        return RespInteger.of(store.append(args[1], args[2].getBytes(StandardCharsets.UTF_8)));
    }

    private Object handleStrlen(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("STRLEN");
        }
        byte[] value = store.get(args[1]);
        return RespInteger.of(value == null ? 0 : value.length);
    }

    private Object handleIncrement(String[] args, long delta) {
        String command = delta > 0 ? "INCR" : "DECR";
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments(command);
        }
        return incrementResult(args[1], delta);
    }

    private Object handleIncrementBy(String[] args, long sign) {
        String command = sign > 0 ? "INCRBY" : "DECRBY";
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments(command);
        }
        try {
            long delta = Long.parseLong(args[2]);
            return incrementResult(args[1], sign > 0 ? delta : Math.negateExact(delta));
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        } catch (ArithmeticException e) {
            return RespError.of("ERR", "increment or decrement would overflow");
        }
    }

    private Object incrementResult(String key, long delta) {
        try {
            return RespInteger.of(store.increment(key, delta));
        } catch (IllegalArgumentException e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    private Object handlePexpire(String[] args) {
        if (args.length != 3) {
            return RespError.wrongNumberOfArguments("PEXPIRE");
        }
        try {
            return RespInteger.of(store.pexpire(args[1], Long.parseLong(args[2])) ? 1 : 0);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        }
    }

    private Object handlePttl(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("PTTL");
        }
        return RespInteger.of(store.pttl(args[1]));
    }

    private Object handleInfo(String[] args) {
        if (args.length > 2) {
            return RespError.wrongNumberOfArguments("INFO");
        }
        String info = "# Server\r\n" +
                "z-cache_version:1.0.0\r\n" +
                "redis_compatible:resp2\r\n\r\n" +
                "# Stats\r\n" +
                "keyspace_hits:" + store.getHits() + "\r\n" +
                "keyspace_misses:" + store.getMisses() + "\r\n" +
                "evicted_keys:" + store.getEvictions() + "\r\n" +
                "max_entries:" + store.getMaxEntries() + "\r\n" +
                "hit_rate:" + String.format(java.util.Locale.ROOT, "%.6f", hitRate()) + "\r\n" +
                "db0_keys:" + store.dbsize() + "\r\n";
        return RespBulkString.of(info);
    }

    private double hitRate() {
        long hits = store.getHits();
        long misses = store.getMisses();
        return hits + misses == 0 ? 0.0 : (double) hits / (hits + misses);
    }

    private Object handleType(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("TYPE");
        }
        return RespSimpleString.of(store.exists(args[1]) ? "string" : "none");
    }

    /**
     * 处理KEYS命令
     *
     * @param args 命令参数
     * @return 匹配的键列表
     */
    private Object handleKeys(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("KEYS");
        }
        List<RespBulkString> keys = new ArrayList<>();
        for (String key : store.keys(args[1])) {
            keys.add(RespBulkString.of(key));
        }
        return RespArray.of(keys.stream().map(k -> (Object) k).toArray());
    }
}
