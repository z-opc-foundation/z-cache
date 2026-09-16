package com.zifang.z.cache.client;

/**
 * 连接状态枚举
 *
 * @author zifang
 * @since 1.0.0
 */
public enum ConnectionState {
    /**
     * 已断开连接
     */
    DISCONNECTED,
    
    /**
     * 连接中
     */
    CONNECTING,
    
    /**
     * 已连接
     */
    CONNECTED,
    
    /**
     * 认证中
     */
    AUTHENTICATING,
    
    /**
     * 已认证
     */
    AUTHENTICATED,
    
    /**
     * 已关闭
     */
    CLOSED,
    
    /**
     * 错误状态
     */
    ERROR
}
