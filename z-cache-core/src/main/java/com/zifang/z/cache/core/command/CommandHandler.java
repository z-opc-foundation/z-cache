package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command handler for Redis commands
 * Processes RESP arrays and executes commands
 */
public class CommandHandler {
    private static final Logger logger = LogManager.getLogger(CommandHandler.class);

    private final MemoryStore store;

    public CommandHandler(MemoryStore store) {
        this.store = store;
    }

    /**
     * Handle a RESP request (must be an array for commands)
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
                case "PERSIST":
                    return handlePersist(args);
                case "SETEX":
                    return handleSetex(args);
                case "PSETEX":
                    return handlePsetex(args);

                // Key management
                case "KEYS":
                    return handleKeys(args);
                case "DBSIZE":
                    return RespInteger.of(store.dbsize());
                case "FLUSHDB":
                    store.flush();
                    return RespSimpleString.of("OK");

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

    // ==================== Connection Commands ====================

    private Object handlePing(String[] args) {
        if (args.length == 1) {
            return RespSimpleString.of("PONG");
        } else if (args.length == 2) {
            return RespBulkString.of(args[1]);
        } else {
            return RespError.wrongNumberOfArguments("PING");
        }
    }

    private Object handleEcho(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("ECHO");
        }
        return RespBulkString.of(args[1]);
    }

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

    private Object handleDel(String[] args) {
        if (args.length < 2) {
            return RespError.wrongNumberOfArguments("DEL");
        }
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, keys.length);
        long deleted = store.del(keys);
        return RespInteger.of(deleted);
    }

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

    private Object handleTtl(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("TTL");
        }
        String key = args[1];
        long ttl = store.ttl(key);
        return RespInteger.of(ttl);
    }

    private Object handlePersist(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("PERSIST");
        }
        String key = args[1];
        boolean result = store.persist(key);
        return RespInteger.of(result ? 1 : 0);
    }

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

    private Object handleKeys(String[] args) {
        if (args.length != 2) {
            return RespError.wrongNumberOfArguments("KEYS");
        }
        String pattern = args[1];
        // For MVP, only support "*" pattern (all keys)
        if (!"*".equals(pattern)) {
            // TODO: Implement glob pattern matching
            return RespError.of("ERR", "pattern matching not fully supported in MVP");
        }
        // Collect all non-expired keys
        List<RespBulkString> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = store.dbsize() > 0 ? getKeyIterator() : java.util.Collections.emptyIterator(); it.hasNext(); ) {
            String key = it.next();
            // Just try to get it - this will handle expiration
            if (store.exists(key)) {
                keys.add(RespBulkString.of(key));
            }
        }
        return RespArray.of(keys.stream().map(k -> (Object) k).toArray());
    }

    private java.util.Iterator<String> getKeyIterator() {
        // Access to keys through a hack since we don't expose the keySet directly
        // In a real implementation, we'd have a proper key iterator
        try {
            java.lang.reflect.Field field = MemoryStore.class.getDeclaredField("store");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) field.get(store);
            return map.keySet().iterator();
        } catch (Exception e) {
            return java.util.Collections.emptyIterator();
        }
    }
}
