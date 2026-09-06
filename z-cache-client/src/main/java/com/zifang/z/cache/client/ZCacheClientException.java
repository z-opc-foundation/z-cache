package com.zifang.z.cache.client;

/**
 * Exception thrown by ZCacheClient
 */
public class ZCacheClientException extends RuntimeException {

    public ZCacheClientException(String message) {
        super(message);
    }

    public ZCacheClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public ZCacheClientException(Throwable cause) {
        super(cause);
    }
}
