package com.zifang.z.cache.common.protocol;

import java.util.Objects;

/**
 * RESP Integer type
 * Format: :123\r\n
 */
public final class RespInteger {

    // Common constants
    public static final RespInteger ZERO = new RespInteger(0);
    public static final RespInteger ONE = new RespInteger(1);
    public static final RespInteger MINUS_ONE = new RespInteger(-1);
    private final long value;

    private RespInteger(long value) {
        this.value = value;
    }

    // Factory methods
    public static RespInteger of(long value) {
        return new RespInteger(value);
    }

    public static RespInteger of(int value) {
        return new RespInteger(value);
    }

    public long getValue() {
        return value;
    }

    public int intValue() {
        return (int) value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RespInteger that = (RespInteger) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "RespInteger{" + value + "}";
    }
}
