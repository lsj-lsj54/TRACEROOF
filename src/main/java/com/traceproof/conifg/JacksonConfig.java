package com.traceproof.conifg;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Jackson 序列化配置
 * <p>
 * 解决 Long 主键在浏览器端精度丢失的问题：
 * 数据库使用雪花算法生成 id（例如 2109210339268718593，19 位），
 * 而 JavaScript 的 Number 只能安全表示 2^53 - 1（9007199254740991，16 位），
 * JSON.parse 会把 2109210339268718593 变成 2109210339268718600。
 * <p>
 * 结果就是：列表接口拿到的 id 是错的，再用它去请求详情、删除、编辑等接口，
 * 后端按错误的 id 查库必然查不到，报「请求数据不存在」。
 * <p>
 * 这里的处理方式是：只把超出 JS 安全范围的 Long 序列化成字符串，
 * 范围内的仍然保持数字（因此分页的 total、size 等依然是数字，不影响前端计算）。
 *
 * @author lsj
 */
@Configuration
public class JacksonConfig {

    /** JavaScript 能安全表示的最大整数：2^53 - 1 */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    /**
     * 自定义 Long 序列化：超出 JS 安全范围时输出字符串
     *
     * @return Jackson 构建器定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, new SafeLongSerializer());
            module.addSerializer(Long.TYPE, new SafeLongSerializer());
            builder.modulesToInstall(module);
        };
    }

    /**
     * 安全的 Long 序列化器
     * <p>
     * 超出 JS 安全整数范围时输出字符串，否则输出数字。
     */
    public static class SafeLongSerializer extends JsonSerializer<Long> {

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            // 超出 JS 安全整数范围，必须用字符串传，否则前端会算错
            if (value > JS_MAX_SAFE_INTEGER || value < -JS_MAX_SAFE_INTEGER) {
                gen.writeString(String.valueOf(value));
            } else {
                gen.writeNumber(value);
            }
        }
    }
}
