package com.zifang.z.cache.core.serialize;

import io.zifu.z.serialize.annotation.FieldType;
import io.zifu.z.serialize.annotation.ZField;
import io.zifu.z.serialize.annotation.ZMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ZCacheCodec 与 z-util-serialize 的集成。
 */
class ZCacheCodecTest {

    @ZMessage(id = 900, name = "cache.TestUser")
    public static class TestUser {
        @ZField(id = 1, type = FieldType.VARINT)
        public long id;

        @ZField(id = 2, type = FieldType.LENGTH_DELIMITED)
        public String name;

        @ZField(id = 3, type = FieldType.VARINT)
        public int age;

        public TestUser() {}
    }

    @Test
    void testSerializeDeserializeZMessage() throws IOException {
        TestUser user = new TestUser();
        user.id = 42L;
        user.name = "Alice";
        user.age = 30;

        byte[] bytes = ZCacheCodec.serialize(user);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        TestUser loaded = ZCacheCodec.deserialize(bytes, TestUser.class);
        assertNotNull(loaded);
        assertEquals(42L, loaded.id);
        assertEquals("Alice", loaded.name);
        assertEquals(30, loaded.age);
    }

    @Test
    void testSizeOf() throws IOException {
        TestUser user = new TestUser();
        user.id = 1L;
        user.name = "Bob";
        user.age = 25;

        int size = ZCacheCodec.sizeOf(user);
        assertTrue(size > 0);
        assertEquals(size, ZCacheCodec.serialize(user).length);
    }

    @Test
    void testIsZSerializable() {
        assertTrue(ZCacheCodec.isZSerializable(TestUser.class));
        assertFalse(ZCacheCodec.isZSerializable(String.class));
    }

    @Test
    void testSerializeNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ZCacheCodec.serialize(null));
    }

    @Test
    void testDeserializeNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ZCacheCodec.deserialize(null, TestUser.class));
    }
}
