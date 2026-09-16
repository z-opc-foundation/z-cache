package com.zifang.z.cache.client;

/**
 * ZCache 客户端异常
 *
 * @author zifang
 * @since 1.0.0
 */
public class ZCacheClientException extends RuntimeException {

    /**
     * 构造函数
     *
     * @param message 异常信息
     */
    public ZCacheClientException(String message) {
        super(message);
    }

    /**
     * 构造函数
     *
     * @param message 异常信息
     * @param cause   引发异常的原因
     */
    public ZCacheClientException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造函数
     *
     * @param cause 引发异常的原因
     */
    public ZCacheClientException(Throwable cause) {
        super(cause);
    }
}
