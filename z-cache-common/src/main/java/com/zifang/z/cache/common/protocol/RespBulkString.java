package com.zifang.z.cache.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * RESP Bulk String type
 * Format: $5\r\nhello\r\n  or  $-1\r\n (null)
 */
public final class RespBulkString {

    // Common constants
    private static final RespBulkString NULL_INSTANCE = new RespBulkString(null, true);
    private static final RespBulkString EMPTY_INSTANCE = new RespBulkString(new byte[0], false);
    private final byte[] data;
    private final boolean isNull;

    private RespBulkString(byte[] data, boolean isNull) {
        this.data = data;
        this.isNull = isNull;
    }

    // Factory methods
    public static RespBulkString of(byte[] data) {
        if (data == null) {
            return new RespBulkString(null, true);
        }
        return new RespBulkString(data.clone(), false);
    }

    public static RespBulkString of(String str) {
        if (str == null) {
            return new RespBulkString(null, true);
        }
        return new RespBulkString(str.getBytes(StandardCharsets.UTF_8), false);
    }

    public static RespBulkString nullBulkString() {
        return NULL_INSTANCE;
    }

    public static RespBulkString empty() {
        return EMPTY_INSTANCE;
    }

    public byte[] getData() {
        if (isNull) {
            return null;
        }
        return data.clone(); // Return copy to protect immutability
    }

    public String getString() {
        if (isNull || data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public int length() {
        if (isNull || data == null) {
            return -1;
        }
        return data.length;
    }

    public boolean isNull() {
        return isNull;
    }

    public boolean isEmpty() {
        return !isNull && data != null && data.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespBulkString that = (RespBulkString) o;
        if (isNull != that.isNull) return false;
        if (isNull) return true; // Both null
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        if (isNull) {
            return 0;
        }
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        if (isNull) {
            return "RespBulkString{null}";
        }
        String content = getString();
        if (content != null && content.length() <= 100) {
            return "RespBulkString{\"" + content + "\"}";
        } else {
            return "RespBulkString{" + data.length + " bytes}";
        }
    }
}
