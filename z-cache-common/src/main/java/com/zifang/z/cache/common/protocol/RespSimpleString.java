package com.zifang.z.cache.common.protocol;

import java.util.Objects;

/**
 * RESP Simple String type
 * Format: +OK\r\n
 */
public final class RespSimpleString {

    // Common constants
    public static final RespSimpleString OK = new RespSimpleString("OK");
    public static final RespSimpleString PONG = new RespSimpleString("PONG");
    private final String value;

    private RespSimpleString(String value) {
        this.value = Objects.requireNonNull(value, "value cannot be null");
        // Simple strings cannot contain \r or \n
        if (value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Simple string cannot contain CR or LF");
        }
    }

    // Factory methods
    public static RespSimpleString of(String value) {
        return new RespSimpleString(value);
    }

    public static RespSimpleString ok() {
        return OK;
    }

    public static RespSimpleString pong() {
        return PONG;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RespSimpleString that = (RespSimpleString) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "RespSimpleString{\"" + value + "\"}";
    }
}
