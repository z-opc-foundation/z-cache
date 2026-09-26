package com.zifang.z.cache.core.pubsub;

import com.zifang.z.cache.common.protocol.RespArray;
import com.zifang.z.cache.common.protocol.RespBulkString;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布订阅管理器，实现 SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE/PUBLISH 命令。
 * <p>
 * 支持两种订阅模式：
 * <ul>
 *   <li>精确频道订阅 — SUBSCRIBE/UNSUBSCRIBE</li>
 *   <li>模式订阅 — PSUBSCRIBE/PUNSUBSCRIBE，支持 * 和 ? 通配符</li>
 * </ul>
 * <p>
 * 线程安全：所有内部数据结构均使用 {@link ConcurrentHashMap}。
 *
 * @author zifang
 * @since 1.0.2
 */
public class PubSubManager {

    private static final Logger logger = LogManager.getLogger(PubSubManager.class);

    /** 这条连接当前持有的精确频道订阅数（CLIENT LIST 的 {@code sub=} 用它，不再写死 0）。 */
    public int channelCount(ChannelHandlerContext ctx) {
        Set<String> channels = ctx == null ? null : clientChannels.get(ctx);
        return channels == null ? 0 : channels.size();
    }

    /** 这条连接当前持有的模式订阅数（CLIENT LIST 的 {@code psub=}）。 */
    public int patternCount(ChannelHandlerContext ctx) {
        Set<String> patterns = ctx == null ? null : clientPatterns.get(ctx);
        return patterns == null ? 0 : patterns.size();
    }

    /**
     * 检查客户端是否有任何订阅（精确频道或模式）。
     *
     * @param ctx 客户端连接上下文
     * @return true 表示该客户端有至少一个活跃订阅
     */
    public boolean isSubscribed(ChannelHandlerContext ctx) {
        Set<String> channels = clientChannels.get(ctx);
        if (channels != null && !channels.isEmpty()) {
            return true;
        }
        Set<String> patterns = clientPatterns.get(ctx);
        return patterns != null && !patterns.isEmpty();
    }

    /**
     * 精确频道 -> 订阅者集合 */
    private final ConcurrentHashMap<String, Set<ChannelHandlerContext>> channelSubscribers = new ConcurrentHashMap<>();

    /** 模式（pattern） -> 订阅者集合 */
    private final ConcurrentHashMap<String, Set<ChannelHandlerContext>> patternSubscribers = new ConcurrentHashMap<>();

    /** 连接 -> 该连接订阅的精确频道集合 */
    private final ConcurrentHashMap<ChannelHandlerContext, Set<String>> clientChannels = new ConcurrentHashMap<>();

    /** 连接 -> 该连接订阅的模式集合 */
    private final ConcurrentHashMap<ChannelHandlerContext, Set<String>> clientPatterns = new ConcurrentHashMap<>();

    /**
     * 订阅指定的频道。
     *
     * @param ctx      客户端连接上下文
     * @param channels 要订阅的频道名数组
     */
    public void subscribe(ChannelHandlerContext ctx, String... channels) {
        Set<String> subscribed = clientChannels.computeIfAbsent(ctx, k -> ConcurrentHashMap.newKeySet());
        for (String channel : channels) {
            subscribed.add(channel);
            Set<ChannelHandlerContext> subscribers = channelSubscribers.computeIfAbsent(channel,
                    k -> ConcurrentHashMap.newKeySet());
            subscribers.add(ctx);
        }
        logger.debug("Client {} subscribed to channels: {}", ctx.channel().remoteAddress(), (Object) channels);
    }

    /**
     * 取消订阅指定的频道。
     *
     * @param ctx      客户端连接上下文
     * @param channels 要取消订阅的频道名数组
     */
    public void unsubscribe(ChannelHandlerContext ctx, String... channels) {
        Set<String> subscribed = clientChannels.get(ctx);
        if (subscribed == null) {
            return;
        }
        for (String channel : channels) {
            subscribed.remove(channel);
            Set<ChannelHandlerContext> subscribers = channelSubscribers.get(channel);
            if (subscribers != null) {
                subscribers.remove(ctx);
                if (subscribers.isEmpty()) {
                    channelSubscribers.remove(channel, subscribers);
                }
            }
        }
        if (subscribed.isEmpty()) {
            clientChannels.remove(ctx);
        }
        logger.debug("Client {} unsubscribed from channels: {}", ctx.channel().remoteAddress(), (Object) channels);
    }

    /**
     * 模式订阅。
     *
     * @param ctx      客户端连接上下文
     * @param patterns 要订阅的模式数组（支持 * 和 ? 通配符）
     */
    public void psubscribe(ChannelHandlerContext ctx, String... patterns) {
        Set<String> subscribed = clientPatterns.computeIfAbsent(ctx, k -> ConcurrentHashMap.newKeySet());
        for (String pattern : patterns) {
            subscribed.add(pattern);
            Set<ChannelHandlerContext> subscribers = patternSubscribers.computeIfAbsent(pattern,
                    k -> ConcurrentHashMap.newKeySet());
            subscribers.add(ctx);
        }
        logger.debug("Client {} pattern-subscribed to: {}", ctx.channel().remoteAddress(), (Object) patterns);
    }

    /**
     * 取消模式订阅。
     *
     * @param ctx      客户端连接上下文
     * @param patterns 要取消订阅的模式数组
     */
    public void punsubscribe(ChannelHandlerContext ctx, String... patterns) {
        Set<String> subscribed = clientPatterns.get(ctx);
        if (subscribed == null) {
            return;
        }
        for (String pattern : patterns) {
            subscribed.remove(pattern);
            Set<ChannelHandlerContext> subscribers = patternSubscribers.get(pattern);
            if (subscribers != null) {
                subscribers.remove(ctx);
                if (subscribers.isEmpty()) {
                    patternSubscribers.remove(pattern, subscribers);
                }
            }
        }
        if (subscribed.isEmpty()) {
            clientPatterns.remove(ctx);
        }
        logger.debug("Client {} pattern-unsubscribed from: {}", ctx.channel().remoteAddress(), (Object) patterns);
    }

    /**
     * 发布消息到指定频道，向精确匹配和模式匹配的订阅者推送消息。
     *
     * @param channel 频道名
     * @param message 消息内容
     * @return 实际接收到消息的订阅者数量
     */
    public int publish(String channel, String message) {
        int count = 0;

        // 向精确频道订阅者发送消息
        Set<ChannelHandlerContext> exactSubscribers = channelSubscribers.get(channel);
        if (exactSubscribers != null) {
            // 构建 RESP 数组消息：["message", channel, message]
            Object[] messageArray = new Object[]{
                    RespBulkString.of("message"),
                    RespBulkString.of(channel),
                    RespBulkString.of(message)
            };
            for (ChannelHandlerContext subscriber : exactSubscribers) {
                if (sendToClient(subscriber, messageArray)) {
                    count++;
                }
            }
        }

        // 向模式订阅者发送消息
        for (Map.Entry<String, Set<ChannelHandlerContext>> entry : patternSubscribers.entrySet()) {
            String pattern = entry.getKey();
            if (matchPattern(pattern, channel)) {
                Set<ChannelHandlerContext> patternSubs = entry.getValue();
                // 构建 RESP 数组消息：["pmessage", pattern, channel, message]
                Object[] pmessageArray = new Object[]{
                        RespBulkString.of("pmessage"),
                        RespBulkString.of(pattern),
                        RespBulkString.of(channel),
                        RespBulkString.of(message)
                };
                for (ChannelHandlerContext subscriber : patternSubs) {
                    if (sendToClient(subscriber, pmessageArray)) {
                        count++;
                    }
                }
            }
        }

        logger.debug("Published message to channel '{}', received by {} subscribers", channel, count);
        return count;
    }

    /**
     * 获取所有活跃频道（当前有订阅者的频道）。
     *
     * @param pattern 模式字符串，null 或 "*" 表示所有频道
     * @return 匹配的活跃频道集合
     */
    public Set<String> getChannels(String pattern) {
        if (pattern == null || "*".equals(pattern)) {
            return Collections.unmodifiableSet(channelSubscribers.keySet());
        }
        Set<String> matched = new HashSet<>();
        String regex = globToRegex(pattern);
        for (String channel : channelSubscribers.keySet()) {
            if (channel.matches(regex)) {
                matched.add(channel);
            }
        }
        return matched;
    }

    /**
     * 获取指定频道的订阅者数量。
     *
     * @param channels 频道名数组
     * @return 频道 -> 订阅数映射
     */
    public Map<String, Integer> getNumSub(String... channels) {
        Map<String, Integer> result = new HashMap<>();
        for (String channel : channels) {
            Set<ChannelHandlerContext> subscribers = channelSubscribers.get(channel);
            result.put(channel, subscribers == null ? 0 : subscribers.size());
        }
        return result;
    }

    /**
     * 获取当前模式订阅的总频道数（所有模式匹配的订阅者总数）。
     *
     * @return 模式订阅总数
     */
    public int getNumPat() {
        int count = 0;
        for (Set<ChannelHandlerContext> subscribers : patternSubscribers.values()) {
            count += subscribers.size();
        }
        return count;
    }

    /**
     * 客户端断开连接时，清理该连接的所有订阅。
     *
     * @param ctx 客户端连接上下文
     */
    public void removeClient(ChannelHandlerContext ctx) {
        // 清理精确频道订阅
        Set<String> channels = clientChannels.remove(ctx);
        if (channels != null) {
            for (String channel : channels) {
                Set<ChannelHandlerContext> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove(ctx);
                    if (subscribers.isEmpty()) {
                        channelSubscribers.remove(channel, subscribers);
                    }
                }
            }
        }

        // 清理模式订阅
        Set<String> patterns = clientPatterns.remove(ctx);
        if (patterns != null) {
            for (String pattern : patterns) {
                Set<ChannelHandlerContext> subscribers = patternSubscribers.get(pattern);
                if (subscribers != null) {
                    subscribers.remove(ctx);
                    if (subscribers.isEmpty()) {
                        patternSubscribers.remove(pattern, subscribers);
                    }
                }
            }
        }

        logger.debug("Cleaned up all subscriptions for client {}", ctx.channel().remoteAddress());
    }

    /**
     * 向客户端发送消息，发送失败时静默移除该订阅者。
     *
     * @param ctx     客户端连接上下文
     * @param message RESP 消息数组
     * @return true 表示发送成功
     */
    private boolean sendToClient(ChannelHandlerContext ctx, Object[] message) {
        try {
            ctx.writeAndFlush(RespArray.of(message));
            return true;
        } catch (Exception e) {
            logger.warn("Failed to send message to client {}, removing subscription: {}",
                    ctx.channel().remoteAddress(), e.getMessage());
            removeClient(ctx);
            return false;
        }
    }

    /**
     * 简单通配符模式匹配。
     * <ul>
     *   <li>{@code *} 匹配任意字符序列（包括空序列）</li>
     *   <li>{@code ?} 匹配单个字符</li>
     * </ul>
     *
     * @param pattern 通配符模式
     * @param text    要匹配的文本
     * @return true 表示匹配成功
     */
    private static boolean matchPattern(String pattern, String text) {
        return matchPattern(pattern, 0, text, 0);
    }

    /**
     * 递归通配符匹配实现。
     */
    private static boolean matchPattern(String pattern, int pi, String text, int ti) {
        while (pi < pattern.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // '*' 可以匹配零个或多个字符
                pi++;
                if (pi >= pattern.length()) {
                    return true; // 模式以 '*' 结尾，匹配所有
                }
                // 尝试 text 中每个位置
                for (int i = ti; i <= text.length(); i++) {
                    if (matchPattern(pattern, pi, text, i)) {
                        return true;
                    }
                }
                return false;
            } else if (pc == '?') {
                // '?' 必须匹配一个字符
                if (ti >= text.length()) {
                    return false;
                }
                pi++;
                ti++;
            } else {
                // 普通字符必须精确匹配
                if (ti >= text.length() || pc != text.charAt(ti)) {
                    return false;
                }
                pi++;
                ti++;
            }
        }
        return ti == text.length();
    }

    /**
     * 将 glob 通配符模式转换为正则表达式。
     */
    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if (".\\[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }
}
