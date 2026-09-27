package com.zifang.z.cache.common.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "一条命令回<em>多帧</em>"的顶层载体 —— 只给 RESP 编码器认，不是数组。
 *
 * <p>
 * 为什么要有这个类：pubsub 那四家一次给多个名字时，上游是一个名字发<em>一条独立的确认帧</em>
 * （5.0.14 {@code pubsub.c:79-82} 每手各推一条，命令层是 {@code :280-281} 那样逐名循环），
 * 而"把 N 条包成一条 {@code *N}"在本仓库的编码规则下是 {@code RespArray} 的天然形状 ——
 * 于是只要图省事 {@code return RespArray.of(frames)}，线上就是一条嵌套数组。
 * 这个类型把"多条"和"一条数组"在类型上分开，编码器只在<em>顶层</em>展开成 N 帧。
 * </p>
 *
 * <p>
 * 不变式：本对象<em>不许</em>出现在数组元素的位置上（那样会被就地展开成 N 帧，把外层数组的长度
 * 对不上号）。事务里排队这几家时，{@code EXEC} 那一腿会把元素换成 {@link RespArray}，
 * 这条不变式由 {@code RedisPubSubConfirmFrameTest} 的结构判据钉住。
 * </p>
 */
public final class RespFrames {

    private final List<Object> frames;

    private RespFrames(List<Object> frames) {
        this.frames = frames;
    }

    public static RespFrames of(List<Object> frames) {
        if (frames == null) {
            throw new IllegalArgumentException("RespFrames 不接受 null 帧表（要空请回一条帧）");
        }
        return new RespFrames(new ArrayList<>(frames));
    }

    public static RespFrames of(Object... frames) {
        return of(Arrays.asList(frames));
    }

    /** 顶层那一帧一帧要写的字节序就是这里的迭代序。 */
    public List<Object> frames() {
        return Collections.unmodifiableList(frames);
    }

    public int size() {
        return frames.size();
    }
}
