package com.traceproof.conifg;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的来源，多个用逗号分隔
     * <p>
     * 默认放行全部来源（*），方便本地开发与前后端分离调试；
     * 生产环境建议收紧成自己的域名，例如：
     * <pre>
     *   APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://evidence.example.com
     * </pre>
     * <p>
     * 为什么建议收紧：下面开了 allowCredentials(true)（浏览器要带 JSESSIONID 维持登录态），
     * 来源放行「任意」意味着任何第三方站点都能让用户的浏览器带着 Cookie 调本服务接口、
     * 并读取响应内容。同源部署（Nginx 反代 /api）时压根用不到跨域，收紧成具体域名最稳。
     */
    @Value("${app.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    /**
     * 配置跨域映射
     *
     * @param registry 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(StrUtil::trim)
                .filter(StrUtil::isNotEmpty)
                .toArray(String[]::new);
        if (patterns.length == 0) {
            patterns = new String[]{"*"};
        }
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
