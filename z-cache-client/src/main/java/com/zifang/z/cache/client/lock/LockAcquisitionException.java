package com.zifang.z.cache.client.lock;

/**
 * 锁获取失败异常（包装服务端错误，如网络中断、RESP 解析失败等）。
 *
 * <p>注意：tryLock 返回 null 表示"锁已被占用"，这不算异常。
 * 此异常仅在服务端错误（如连接中断、RESP 格式异常）时抛出。
 *
 * @author zifang
 * @since 1.3.0
 */
public class LockAcquisitionException extends RuntimeException {

    /**
     * 使用指定消息构造异常。
     *
     * @param message 异常描述
     */
    public LockAcquisitionException(String message) {
        super(message);
    }

    /**
     * 使用指定消息和原因构造异常。
     *
     * @param message 异常描述
     * @param cause   原始异常
     */
    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
