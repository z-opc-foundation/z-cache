package com.zifang.z.cache.common.protocol;

/**
 * RESP (Redis Serialization Protocol) data types
 */
public enum RespType {
    SIMPLE_STRING('+'),      // +OK\r\n
    ERROR('-'),              // -ERR message\r\n
    INTEGER(':'),            // :123\r\n
    BULK_STRING('$'),        // $5\r\nhello\r\n
    ARRAY('*');              // *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n

    private final char prefix;

    RespType(char prefix) {
        this.prefix = prefix;
    }

    public static RespType fromPrefix(char prefix) {
        for (RespType type : values()) {
            if (type.prefix == prefix) {
                return type;
            }
        }
        return null;
    }

    public char getPrefix() {
        return prefix;
    }
}
