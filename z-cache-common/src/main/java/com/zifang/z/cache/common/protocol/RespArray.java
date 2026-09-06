package com.zifang.z.cache.common.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RESP Array type
 * Format: *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n
 */
public final class RespArray {

    // Common constants
    private static final RespArray NULL_INSTANCE = new RespArray(null, true);
    private static final RespArray EMPTY_INSTANCE = new RespArray(Collections.emptyList(), false);
    private final List<Object> elements;
    private final boolean isNull;

    public RespArray(List<Object> elements, boolean isNull) {
        this.elements = elements;
        this.isNull = isNull;
    }

    // Factory methods
    public static RespArray of(List<Object> elements) {
        if (elements == null) {
            return new RespArray(null, true);
        }
        return new RespArray(new ArrayList<>(elements), false);
    }

    public static RespArray of(Object... elements) {
        if (elements == null) {
            return new RespArray(null, true);
        }
        return new RespArray(Arrays.asList(elements), false);
    }

    public static RespArray nullArray() {
        return NULL_INSTANCE;
    }

    public static RespArray empty() {
        return EMPTY_INSTANCE;
    }

    public static RespArray command(String command, Object... args) {
        List<Object> elements = new ArrayList<>(args.length + 1);
        elements.add(RespBulkString.of(command));
        for (Object arg : args) {
            if (arg instanceof String) {
                elements.add(RespBulkString.of((String) arg));
            } else if (arg instanceof byte[]) {
                elements.add(RespBulkString.of((byte[]) arg));
            } else if (arg instanceof RespBulkString) {
                elements.add(arg);
            } else {
                elements.add(RespBulkString.of(String.valueOf(arg)));
            }
        }
        return new RespArray(elements, false);
    }

    public List<Object> getElements() {
        if (isNull) {
            return null;
        }
        return Collections.unmodifiableList(elements);
    }

    public int size() {
        if (isNull) {
            return -1;
        }
        return elements.size();
    }

    public boolean isEmpty() {
        return !isNull && elements.isEmpty();
    }

    public boolean isNull() {
        return isNull;
    }

    public Object get(int index) {
        if (isNull) {
            throw new IllegalStateException("Cannot get element from null array");
        }
        return elements.get(index);
    }

    /**
     * Convert elements to String array (for command parsing)
     */
    public String[] toStringArray() {
        if (isNull || elements == null) {
            return new String[0];
        }
        String[] result = new String[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            Object elem = elements.get(i);
            if (elem instanceof RespBulkString) {
                result[i] = ((RespBulkString) elem).getString();
            } else if (elem instanceof RespSimpleString) {
                result[i] = ((RespSimpleString) elem).getValue();
            } else if (elem != null) {
                result[i] = elem.toString();
            } else {
                result[i] = null;
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespArray respArray = (RespArray) o;
        if (isNull != respArray.isNull) return false;
        if (isNull) return true;
        return elements.equals(respArray.elements);
    }

    @Override
    public int hashCode() {
        if (isNull) {
            return 0;
        }
        return elements.hashCode();
    }

    @Override
    public String toString() {
        if (isNull) {
            return "RespArray{null}";
        }
        StringBuilder sb = new StringBuilder("RespArray{");
        sb.append("size=").append(elements.size());
        if (elements.size() <= 5) {
            sb.append(", elements=").append(elements);
        }
        sb.append('}');
        return sb.toString();
    }
}
