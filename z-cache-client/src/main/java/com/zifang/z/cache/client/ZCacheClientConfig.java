package com.zifang.z.cache.client;

import java.time.Duration;

/**
 * ZCache客户端配置
 *
 * @author zifang
 * @since 1.0.0
 */
public class ZCacheClientConfig {

    /**
     * 主机地址
     */
    private String host = "localhost";
    
    /**
     * 端口号
     */
    private int port = 6379;
    
    /**
     * 连接超时时间
     */
    private Duration connectTimeout = Duration.ofSeconds(5);
    
    /**
     * 读取超时时间
     */
    private Duration readTimeout = Duration.ofSeconds(5);
    
    /**
     * 写入超时时间
     */
    private Duration writeTimeout = Duration.ofSeconds(5);
    
    /**
     * 是否自动重连
     */
    private boolean autoReconnect = true;
    
    /**
     * 最大重连尝试次数
     */
    private int maxReconnectAttempts = 3;
    
    /**
     * 重连间隔时间
     */
    private Duration reconnectInterval = Duration.ofMillis(100);
    
    /**
     * 是否使用 SSL
     */
    private boolean useSsl = false;
    
    /**
     * 密码
     */
    private String password = null;
    
    /**
     * 数据库索引
     */
    private int database = 0;

    /**
     * 连接池最大连接数，供集成方复用统一配置。
     */
    private int poolMaxSize = 8;

    /**
     * 默认构造函数
     */
    public ZCacheClientConfig() {
    }

    /**
     * 构造函数，设置主机和端口
     *
     * @param host 主机地址
     * @param port 端口号
     */
    public ZCacheClientConfig(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // Getters and setters

    /**
     * 获取主机地址
     *
     * @return 主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置主机地址
     *
     * @param host 主机地址
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 获取端口号
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置端口号
     *
     * @param port 端口号
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 获取连接超时时间
     *
     * @return 连接超时时间
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时时间
     *
     * @param connectTimeout 连接超时时间
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 获取读取超时时间
     *
     * @return 读取超时时间
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读取超时时间
     *
     * @param readTimeout 读取超时时间
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * 获取写入超时时间
     *
     * @return 写入超时时间
     */
    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * 设置写入超时时间
     *
     * @param writeTimeout 写入超时时间
     */
    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    /**
     * 获取是否自动重连
     *
     * @return 是否自动重连
     */
    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    /**
     * 设置是否自动重连
     *
     * @param autoReconnect 是否自动重连
     */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    /**
     * 获取最大重连尝试次数
     *
     * @return 最大重连尝试次数
     */
    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    /**
     * 设置最大重连尝试次数
     *
     * @param maxReconnectAttempts 最大重连尝试次数
     */
    public void setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    /**
     * 获取重连间隔时间
     *
     * @return 重连间隔时间
     */
    public Duration getReconnectInterval() {
        return reconnectInterval;
    }

    /**
     * 设置重连间隔时间
     *
     * @param reconnectInterval 重连间隔时间
     */
    public void setReconnectInterval(Duration reconnectInterval) {
        this.reconnectInterval = reconnectInterval;
    }

    /**
     * 获取是否使用 SSL
     *
     * @return 是否使用 SSL
     */
    public boolean isUseSsl() {
        return useSsl;
    }

    /**
     * 设置是否使用 SSL
     *
     * @param useSsl 是否使用 SSL
     */
    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    /**
     * 获取密码
     *
     * @return 密码
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置密码
     *
     * @param password 密码
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 获取数据库索引
     *
     * @return 数据库索引
     */
    public int getDatabase() {
        return database;
    }

    /**
     * 设置数据库索引
     *
     * @param database 数据库索引
     */
    public void setDatabase(int database) {
        this.database = database;
    }

    public int getPoolMaxSize() {
        return poolMaxSize;
    }

    public void setPoolMaxSize(int poolMaxSize) {
        if (poolMaxSize <= 0) {
            throw new IllegalArgumentException("poolMaxSize must be positive");
        }
        this.poolMaxSize = poolMaxSize;
    }

    // Builder pattern

    /**
     * 设置主机地址并返回配置对象
     *
     * @param host 主机地址
     * @return 配置对象
     */
    public ZCacheClientConfig withHost(String host) {
        this.host = host;
        return this;
    }

    /**
     * 设置端口号并返回配置对象
     *
     * @param port 端口号
     * @return 配置对象
     */
    public ZCacheClientConfig withPort(int port) {
        this.port = port;
        return this;
    }

    /**
     * 设置连接超时时间并返回配置对象
     *
     * @param timeout 连接超时时间
     * @return 配置对象
     */
    public ZCacheClientConfig withConnectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * 设置读取超时时间并返回配置对象
     *
     * @param timeout 读取超时时间
     * @return 配置对象
     */
    public ZCacheClientConfig withReadTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return this;
    }

    /**
     * 设置密码并返回配置对象
     *
     * @param password 密码
     * @return 配置对象
     */
    public ZCacheClientConfig withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * 设置数据库索引并返回配置对象
     *
     * @param database 数据库索引
     * @return 配置对象
     */
    public ZCacheClientConfig withDatabase(int database) {
        this.database = database;
        return this;
    }

    /**
     * 设置连接池最大大小并返回配置对象
     *
     * @param poolMaxSize 连接池最大大小
     * @return 配置对象
     */
    public ZCacheClientConfig withPoolMaxSize(int poolMaxSize) {
        return setPoolMaxSizeAndReturn(poolMaxSize);
    }

    private ZCacheClientConfig setPoolMaxSizeAndReturn(int poolMaxSize) {
        setPoolMaxSize(poolMaxSize);
        return this;
    }

    /**
     * 设置是否使用 SSL 并返回配置对象
     *
     * @param useSsl 是否使用 SSL
     * @return 配置对象
     */
    public ZCacheClientConfig withUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
        return this;
    }

    // Convenient aliases for test compatibility

    /**
     * 设置主机地址（别名）并返回配置对象
     *
     * @param host 主机地址
     * @return 配置对象
     */
    public ZCacheClientConfig host(String host) {
        return withHost(host);
    }

    /**
     * 设置密码（别名）并返回配置对象。
     */
    public ZCacheClientConfig password(String password) {
        return withPassword(password);
    }

    /**
     * 设置端口号（别名）并返回配置对象
     *
     * @param port 端口号
     * @return 配置对象
     */
    public ZCacheClientConfig port(int port) {
        return withPort(port);
    }

    /**
     * 设置连接超时时间（别名）并返回配置对象
     *
     * @param timeout 连接超时时间
     * @return 配置对象
     */
    public ZCacheClientConfig connectTimeout(Duration timeout) {
        return withConnectTimeout(timeout);
    }

    /**
     * 设置读取超时时间（别名）并返回配置对象
     *
     * @param timeout 读取超时时间
     * @return 配置对象
     */
    public ZCacheClientConfig readTimeout(Duration timeout) {
        return withReadTimeout(timeout);
    }

    /**
     * 设置连接池最大大小（别名）并返回配置对象
     *
     * @param size 连接池最大大小
     * @return 配置对象
     */
    public ZCacheClientConfig poolMaxSize(int size) {
        return withPoolMaxSize(size);
    }

    /**
     * 设置是否使用 SSL（别名）并返回配置对象
     *
     * @param useSsl 是否使用 SSL
     * @return 配置对象
     */
    public ZCacheClientConfig ssl(boolean useSsl) {
        return withUseSsl(useSsl);
    }

}
