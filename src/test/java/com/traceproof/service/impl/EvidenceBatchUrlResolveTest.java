package com.traceproof.service.impl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批量抓取时影像地址解析的测试
 * <p>
 * 解析逻辑不依赖 Spring 容器与网络，因此可以直接测。
 * 这里覆盖的是最容易出错的一点：必应把原图直链放在 murl 属性里，
 * src 只是站内缩略图，取错了会导致抓下来的图又小又糊。
 */
class EvidenceBatchUrlResolveTest {

    private static final String BASE = "https://cn.bing.com/images/async?q=cat&mmasync=1";

    private final EvidenceServiceImpl service = new EvidenceServiceImpl();

    /**
     * 反射调用私有的 resolveImageUrl
     *
     * @param imgElement 影像节点
     * @return 解析结果
     */
    private String resolve(Element imgElement) throws Exception {
        Method method = EvidenceServiceImpl.class.getDeclaredMethod(
                "resolveImageUrl", Element.class);
        method.setAccessible(true);
        return (String) method.invoke(service, imgElement);
    }

    /** 把一段 HTML 片段解析成 img 节点，带 baseUri（与真实抓取时一致） */
    private Element img(String html) {
        return Jsoup.parse(html, BASE).select("img").first();
    }

    @Test
    @DisplayName("应优先取 murl（原图直链），而不是 src 的缩略图")
    void shouldPreferMurl() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"/th?id=thumb\" "
                + "murl=\"https://example.com/real-photo.jpg\">");
        assertEquals("https://example.com/real-photo.jpg", resolve(element));
    }

    @Test
    @DisplayName("murl 为协议相对地址时应补上 https")
    void shouldFixProtocolRelativeUrl() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"/th?id=thumb\" "
                + "murl=\"//example.com/photo.png\">");
        assertEquals("https://example.com/photo.png", resolve(element));
    }

    @Test
    @DisplayName("没有 murl 时应退回 data-src")
    void shouldFallbackToDataSrc() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"/th?id=thumb\" "
                + "data-src=\"https://example.com/lazy.jpg\">");
        assertEquals("https://example.com/lazy.jpg", resolve(element));
    }

    @Test
    @DisplayName("只有相对 src 时应补全成绝对地址")
    void shouldResolveRelativeSrc() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"/images/a.jpg\">");
        assertEquals("https://cn.bing.com/images/a.jpg", resolve(element));
    }

    @Test
    @DisplayName("站外绝对 src 应原样返回")
    void shouldKeepAbsoluteSrc() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"https://other.com/x.jpg\">");
        assertEquals("https://other.com/x.jpg", resolve(element));
    }

    @Test
    @DisplayName("解析不出地址时应返回空串，由调用方跳过")
    void shouldReturnEmptyWhenNothingUsable() throws Exception {
        Element element = img("<img class=\"mimg\">");
        assertEquals("", resolve(element));
    }

    @Test
    @DisplayName("应去掉地址两端的空白")
    void shouldTrimResult() throws Exception {
        Element element = img("<img class=\"mimg\" src=\"/a.jpg\" "
                + "murl=\"  https://example.com/padded.jpg  \">");
        assertEquals("https://example.com/padded.jpg", resolve(element));
    }
}
