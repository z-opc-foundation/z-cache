package com.zifang.z.cache.common.protocol;

import java.util.Objects;

/**
 * RESP Error type
 * Format: -ERR message\r\n
 */
public final class RespError {

    // Common constants
    public static final RespError SYNTAX_ERROR = new RespError("ERR syntax error");
    public static final RespError NO_SUCH_KEY = new RespError("ERR no such key");
    private final String message;

    private RespError(String message) {
        this.message = Objects.requireNonNull(message, "message cannot be null");
    }

    // Factory methods
    public static RespError of(String message) {
        return new RespError(message);
    }

    public static RespError of(String type, String message) {
        return new RespError(type + " " + message);
    }

    // Common error types
    public static RespError err(String message) {
        return new RespError("ERR " + message);
    }

    public static RespError wrongNumberOfArguments(String command) {
        return new RespError("ERR wrong number of arguments for '" + command + "' command");
    }

    public static RespError wrongType(String message) {
        return new RespError("WRONGTYPE " + message);
    }

    public static RespError noSuchKey() {
        return NO_SUCH_KEY;
    }

    public static RespError syntaxError() {
        return SYNTAX_ERROR;
    }

    public static RespError unknownCommand(String command) {
        return new RespError("ERR unknown command '" + command + "'");
    }

    public String getMessage() {
        return message;
    }

    /**
     * Get the error type prefix (e.g., "ERR", "WRONGTYPE")
     */
    public String getErrorType() {
        int spaceIndex = message.indexOf(' ');
        if (spaceIndex > 0) {
            return message.substring(0, spaceIndex);
        }
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespError respError = (RespError) o;
        return Objects.equals(message, respError.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message);
    }

    @Override
    public String toString() {
        return "RespError{\"" + message + "\"}";
    }
}
