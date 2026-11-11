package com.traceproof.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.traceproof.conifg.JacksonConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long 主键序列化测试
 * <p>
 * 验证雪花算法 id 不会在浏览器端因超出 JS 安全整数范围而丢失精度。
 */
class JacksonConfigTest {

    /** 与 JacksonConfig 中一致：2^53 - 1 */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        // 复用生产代码里的序列化器，保证测试与线上行为一致
        module.addSerializer(Long.class, new JacksonConfig.SafeLongSerializer());
        module.addSerializer(Long.TYPE, new JacksonConfig.SafeLongSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    @Test
    void snowflakeIdShouldBeSerializedAsString() throws Exception {
        // 数据库里真实的雪花 id
        long snowflakeId = 2109210339268718593L;
        assertTrue(snowflakeId > JS_MAX_SAFE_INTEGER, "该 id 应当超出 JS 安全范围");

        Map<String, Object> data = new HashMap<>();
        data.put("id", snowflakeId);
        String json = buildObjectMapper().writeValueAsString(data);

        assertEquals("{\"id\":\"" + snowflakeId + "\"}", json);
        // 用 JS 的 Number 解析会变成 2109210339268718600，这里必须保持原样
        assertTrue(json.contains("2109210339268718593"));
    }

    @Test
    void smallLongShouldStayNumber() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("total", 42L);
        String json = buildObjectMapper().writeValueAsString(data);
        // 分页的 total / size 不能被转成字符串，否则前端算不了页数
        assertEquals("{\"total\":42}", json);
    }

    @Test
    void boundaryValueShouldBeTreatedAsSafe() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("value", JS_MAX_SAFE_INTEGER);
        String json = buildObjectMapper().writeValueAsString(data);
        assertEquals("{\"value\":" + JS_MAX_SAFE_INTEGER + "}", json);
    }
}
