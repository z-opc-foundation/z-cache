package com.zifang.z.cache.core.persistence;

/**
 * 自动挡那两条旋钮的<em>唯一持有者</em>，一台服务器一份。
 *
 * <p>它存在的理由是一个结构问题，不是行为问题：这两条旋钮原先是 {@link AofPersistence} 的字段，
 * 于是"旋钮够不够得着"取决于"这一台有没有日志"。{@code CONFIG SET} 因此只能在没有 dataDir 的
 * 那一台回一句"没有落点"，而上游那两条挂在 {@code server} 上（{@code server.h:1077-1078} 的
 * {@code aof_rewrite_perc} / {@code aof_rewrite_min_size}）<b>永远存在</b>：AOF 关着也照样
 * {@code +OK}，只是当下没人读。把旋钮从"日志组件的一部分"改成"服务器有的一份配置"，
 * 那个差别才消失 —— 而不是靠命令层再加一句特判。
 *
 * <p>另一个理由同样具体：{@link AofPersistence} 拿的是<em>这一个对象</em>的引用
 * （{@code RedisServer.initPersistence()} 里 {@code setTuning(scope.aofTuning())}），
 * 不是开场的抄本。抄本会做出一个当场看不出差别的缺陷：{@code CONFIG SET} 改了命令层那一份、
 * 自动挡读的是日志那一份，两边各自"读得到自己"，而那一拍永远用不上新值。
 * 判据在 {@code RedisConfigCommandTest#configSetMovesTheGateThatDecidesTheSwap}。
 */
public final class AofTuning {

    /** 上游 {@code server.h:98}：{@code #define AOF_REWRITE_PERC 100}（长到比底座大一倍才重写）。 */
    public static final int DEFAULT_PERCENTAGE = 100;

    /** 上游 {@code server.h:99}：{@code #define AOF_REWRITE_MIN_SIZE (64*1024*1024)}。 */
    public static final long DEFAULT_MIN_SIZE = 64L * 1024 * 1024;

    private volatile int autoRewritePercentage = DEFAULT_PERCENTAGE;

    private volatile long autoRewriteMinSize = DEFAULT_MIN_SIZE;

    /** 自动挡的百分比门槛（{@code server.aof_rewrite_perc}）；0 表示整个自动挡关掉。 */
    public int getAutoAofRewritePercentage() {
        return autoRewritePercentage;
    }

    /**
     * 设自动挡的百分比门槛。范围照 {@code config.c:1160-1161} 那一对实参：{@code 0..INT_MAX}，
     * 其中 0 是关掉（{@code server.c:1305} 把整数当真假用）。
     *
     * @param percentage 新门槛
     */
    public void setAutoAofRewritePercentage(int percentage) {
        if (percentage < 0) {
            throw new IllegalArgumentException(
                    "Invalid negative percentage for AOF auto rewrite: " + percentage);
        }
        this.autoRewritePercentage = percentage;
    }

    /** 自动挡的体积地板（{@code server.aof_rewrite_min_size}），单位字节。 */
    public long getAutoAofRewriteMinSize() {
        return autoRewriteMinSize;
    }

    /**
     * 设自动挡的体积地板。范围照 {@code config.c:1262-1263}：{@code 0..LONG_MAX}；
     * 上游那一档走的是 {@code memtoll}，所以带单位的写法在命令层就已经换成字节数了。
     *
     * @param bytes 新地板
     */
    public void setAutoAofRewriteMinSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Invalid negative size for AOF auto rewrite: " + bytes);
        }
        this.autoRewriteMinSize = bytes;
    }
}
