package com.zifang.z.cache.client;

/**
 * Connection state enum
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    AUTHENTICATED,
    CLOSED,
    ERROR
}
