package com.traceproof.conifg.sa_token;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;


/**
 * 请求包装过滤器
 *
 * @author pine
 */
@Order(1)
@Component
public class HttpRequestWrapperFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest servletRequest = (HttpServletRequest) request;
            String contentType = servletRequest.getHeader(Header.CONTENT_TYPE.getValue());
            // 用前缀匹配而不是 equals：application/json;charset=UTF-8 也必须包装，
            // 否则鉴权环节读 body 会把流读空，后续 @RequestBody 就收不到参数了
            if (StrUtil.startWithIgnoreCase(contentType, ContentType.JSON.getValue())) {
                // 可以再细粒度一些，只有需要进行档案库权限校验的接口才需要包一层
                chain.doFilter(new RequestWrapper(servletRequest), response);
            } else {
                chain.doFilter(request, response);
            }
        }
    }

}
