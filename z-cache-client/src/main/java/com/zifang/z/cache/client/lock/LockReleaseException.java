package com.zifang.z.cache.client.lock;

/**
 * 锁释放失败异常。
 *
 * <p>当 unlock 服务端返回错误码时抛出。
 * 常见原因：网络中断、锁已被他人持有（Lua 校验失败）、Redis 不可用。
 *
 * @author zifang
 * @since 1.3.0
 */
public class LockReleaseException extends Exception {

    /**
     * 使用指定消息构造异常。
     *
     * @param message 异常描述
     */
    public LockReleaseException(String message) {
        super(message);
    }

    /**
     * 使用指定消息和原因构造异常。
     *
     * @param message 异常描述
     * @param cause   原始异常
     */
    public LockReleaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
