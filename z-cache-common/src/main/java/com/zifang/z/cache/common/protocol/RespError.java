package com.zifang.z.cache.common.protocol;

import java.util.Objects;

/**
 * RESP Error type
 * Format: -ERR message\r\n
 * <p>
 * 构造时会把消息里的 CR/LF 换成空格：这一类型靠 CRLF 结束，见构造函数上的说明。
 */
public final class RespError {

    // Common constants
    public static final RespError SYNTAX_ERROR = new RespError("ERR syntax error");
    public static final RespError NO_SUCH_KEY = new RespError("ERR no such key");
    public static final RespError NOT_AN_INTEGER = new RespError("ERR value is not an integer or out of range");
    /**
     * 位族的三句（SETBIT / GETBIT / BITPOS，250 实测 battery41/42 逐字钉住）。它们与普通整数
     * 那句<b>不同</b>：{@code SETBIT k abc 1} 回的是 "bit offset ..."，而 {@code BITPOS k abc}
     * 回 "value is not an integer ..." —— 合并成一处文案就会有一族说谎，所以各留一句。
     */
    public static final RespError BIT_OFFSET_INVALID =
            new RespError("ERR bit offset is not an integer or out of range");
    public static final RespError BIT_VALUE_INVALID =
            new RespError("ERR bit is not an integer or out of range");
    public static final RespError BIT_ARG_INVALID =
            new RespError("ERR The bit argument must be 1 or 0.");
    /**
     * BITOP NOT 只吃一个源键（250 实测 battery45:14、battery46:15—17，句尾带句号，且
     * {@code NOT}/{@code Not}/{@code nOt} 三种写法都是这一句）。它排在源键的类型检查之前，
     * 也和 syntax error 不是一回事：同一位置的多余 token 换成 AND 就不回这句。
     */
    public static final RespError BITOP_NOT_SINGLE_SOURCE =
            new RespError("ERR BITOP NOT must be called with a single source key.");
    private final String message;

    private RespError(String message) {
        // 与 Redis 的 addReplyErrorLength 同一件事：错误回复是"-...\r\n"，靠 CRLF 结束，
        // 文本里带一个换行就会把这一行劈成几行，后面那段成了客户端从没请求过的响应。
        // 报错文本经常抄着客户端原样的输入（未知命令名、XTRIM 的策略位、异常 message 里
        // 回显的那串数字），所以清洗放在构造这一处，而不是散在每个调用点。
        this.message = sanitize(Objects.requireNonNull(message, "message cannot be null"));
    }

    /** CR/LF 换成空格，其余字节原样保留（错误文本不需要、也不应该被改写成别的形状）。 */
    private static String sanitize(String message) {
        if (message.indexOf('\r') < 0 && message.indexOf('\n') < 0) {
            return message;
        }
        return message.replace('\r', ' ').replace('\n', ' ');
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
        // Redis 打的是 {@code c->cmd->name}，命令表里存的就是小写：实测
        // {@code ZADD k CH} → {@code -ERR wrong number of arguments for 'zadd' command}，
        // 而调用点传进来的都是 {@code "ZADD"} 这种大写。在出口统一一次，比在几十个调用点
        // 各自记得写小写可靠（同一个文案在 CLIENT/DEBUG 那边是要大写的原文，见 unknownCommand）。
        return new RespError("ERR wrong number of arguments for '" + command.toLowerCase(java.util.Locale.ROOT)
                + "' command");
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

    /**
     * 客户端文本过不了 {@link RedisIntegerFormat} 那一档，或者合法但超出目标类型的范围。
     * <p>
     * Redis 侧这两种失败<b>共用同一句</b>（实测 {@code SET k v EX 99999999999999999999} 与
     * {@code EX abcd} 都回这一句），所以这里也不分两个工厂方法。句子要收成一处：命令层曾经
     * 抄了二十几遍，改一个字就得满仓找。
     */
    public static RespError notAnInteger() {
        return NOT_AN_INTEGER;
    }

    public static RespError bitOffsetInvalid() {
        return BIT_OFFSET_INVALID;
    }

    public static RespError bitValueInvalid() {
        return BIT_VALUE_INVALID;
    }

    public static RespError bitArgInvalid() {
        return BIT_ARG_INVALID;
    }

    public static RespError bitopNotSingleSource() {
        return BITOP_NOT_SINGLE_SOURCE;
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
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

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
