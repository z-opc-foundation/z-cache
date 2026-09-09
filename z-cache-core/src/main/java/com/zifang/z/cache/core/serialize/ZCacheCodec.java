package com.zifang.z.cache.core.serialize;

import io.zifu.z.serialize.core.CodecRegistry;
import io.zifu.z.serialize.core.ReflectCodec;
import io.zifu.z.serialize.core.ZDeserializer;
import io.zifu.z.serialize.core.ZSerializer;

import java.io.IOException;

/**
 * z-cache 与 z-util-serialize 的桥接工具。
 *
 * <p>提供将 {@code @ZMessage} 对象序列化为 {@code byte[]} 的能力，
 * 适用于 z-cache 的持久化存储、网络传输等场景。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * // 序列化
 * byte[] bytes = ZCacheCodec.serialize(user);
 *
 * // 反序列化
 * User user = ZCacheCodec.deserialize(bytes, User.class);
 *
 * // 使用 ZCache 的 byte[] 存储
 * zCache.set("user:1", ZCacheCodec.serialize(user));
 * User loaded = ZCacheCodec.deserialize(zCache.get("user:1"), User.class);
 * }</pre>
 *
 * @author zifang
 */
public final class ZCacheCodec {

    private ZCacheCodec() {}

    /**
     * 将对象序列化为字节数组。
     *
     * <p>如果对象带有 {@code @ZMessage} 注解，使用对应的 Codec（优先生成的，其次反射）。
     * 否则使用 Java 序列化作为 fallback。</p>
     *
     * @param object 要序列化的对象（必须非 null）
     * @return 序列化后的字节数组
     * @throws IOException 序列化失败
     */
    public static byte[] serialize(Object object) throws IOException {
        if (object == null) {
            throw new IllegalArgumentException("Cannot serialize null");
        }

        // 检查是否有 Z-Serialize codec
        if (CodecRegistry.isRegistered(object.getClass())) {
            return ZSerializer.INSTANCE.toBytes(object);
        }

        // 检查是否有 @ZMessage 注解（使用反射 codec）
        if (object.getClass().isAnnotationPresent(io.zifu.z.serialize.annotation.ZMessage.class)) {
            return ZSerializer.INSTANCE.toBytes(object);
        }

        // Fallback: Java 序列化
        return javaSerialize(object);
    }

    /**
     * 将字节数组反序列化为对象。
     *
     * @param bytes 序列化的字节数组
     * @param clazz 目标类
     * @param <T>   对象类型
     * @return 反序列化后的对象
     * @throws IOException 反序列化失败
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("Cannot deserialize null");
        }

        // 检查是否有 Z-Serialize codec
        if (CodecRegistry.isRegistered(clazz)) {
            return ZDeserializer.INSTANCE.fromBytes(bytes, clazz);
        }

        // 检查是否有 @ZMessage 注解
        if (clazz.isAnnotationPresent(io.zifu.z.serialize.annotation.ZMessage.class)) {
            return ZDeserializer.INSTANCE.fromBytes(bytes, clazz);
        }

        // Fallback: Java 反序列化
        return javaDeserialize(bytes, clazz);
    }

    /**
     * 获取对象的序列化字节大小（不含 header）。
     * 用于估算存储空间。
     *
     * @param object 要测量的对象
     * @return 序列化后的字节数（含 header）
     * @throws IOException 序列化失败
     */
    public static int sizeOf(Object object) throws IOException {
        return serialize(object).length;
    }

    /**
     * 检查类是否支持 Z-Serialize 序列化。
     */
    public static boolean isZSerializable(Class<?> clazz) {
        return CodecRegistry.isRegistered(clazz)
                || clazz.isAnnotationPresent(io.zifu.z.serialize.annotation.ZMessage.class);
    }

    // ==================== Java 序列化 Fallback ====================

    private static byte[] javaSerialize(Object object) throws IOException {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(256);
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos);
            oos.writeObject(object);
            oos.close();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Java serialization failed for " + object.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T javaDeserialize(byte[] bytes, Class<T> clazz) throws IOException {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais);
            return (T) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Class not found during deserialization: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Java deserialization failed for " + clazz.getName(), e);
        }
    }
}
