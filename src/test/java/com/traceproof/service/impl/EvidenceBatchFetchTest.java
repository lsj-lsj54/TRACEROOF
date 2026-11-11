package com.traceproof.service.impl;

import com.traceproof.exception.BusinessException;
import com.traceproof.model.dto.evidence.EvidenceUploadByBatchRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.FileUploadEnum;
import com.traceproof.model.vo.EvidenceVO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * 批量抓取的取图与命名逻辑测试
 * <p>
 * 关键点：必须从 murl 取原图直链。如果退回 src，拿到的是必应站内缩略图
 * （形如 /th?id=OIP.xxx&w=200），既没有影像后缀，还会被判成「文件类型错误」。
 */
class EvidenceBatchFetchTest {

    /** 必应结果片段的真实结构：缩略图在 src，原图直链在 murl */
    private static final String BING_FRAGMENT = ""
            + "<div class=\"dgControl\">"
            + "  <img class=\"mimg\" src=\"/th?id=OIP.abc123&w=200&h=150&c=7\" "
            + "       murl=\"https://example.com/photos/cat-1.jpg\">"
            + "</div>";

    /** 含多张影像的片段，用于验证序号递增 */
    private static final String BING_FRAGMENT_MULTI = ""
            + "<div class=\"dgControl\">"
            + "  <img class=\"mimg\" src=\"/th?id=OIP.a\" murl=\"https://example.com/photos/cat-1.jpg\">"
            + "  <img class=\"mimg\" src=\"/th?id=OIP.b\" murl=\"https://example.com/photos/dog-2.png\">"
            + "</div>";

    /** 只把文档解析这一步替换掉，其余流程走真实代码 */
    private EvidenceServiceImpl serviceWithDocument(Document document) throws Exception {
        EvidenceServiceImpl service = spy(new EvidenceServiceImpl());
        Method parse = EvidenceServiceImpl.class.getDeclaredMethod("parseSearchPage", String.class);
        parse.setAccessible(true);
        doReturn(document).when(service).parseSearchPage(any(String.class));
        return service;
    }

    private User adminUser() {
        User user = new User();
        user.setId(1L);
        user.setUserRole("admin");
        return user;
    }

    /** 记录每次调用的参数，并模拟上传成功（成功才会让计数递增） */
    private List<EvidenceUploadRequest> captureSuccessfulUploads(EvidenceServiceImpl service) throws Exception {
        List<EvidenceUploadRequest> captured = new ArrayList<>();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            EvidenceVO vo = new EvidenceVO();
            vo.setId((long) captured.size());
            return vo;
        }).when(service).uploadEvidenceByUrl(any(EvidenceUploadRequest.class), any(User.class));
        return captured;
    }

    @Test
    @DisplayName("应把 murl 里的原图直链传给上传接口（而不是站内缩略图）")
    void shouldPassRealImageUrlToUpload() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        Integer count = service.uploadEvidenceByBatch(batchRequest("猫", null, 10), adminUser());

        assertEquals(1, count);
        assertEquals(1, captured.size());
        EvidenceUploadRequest request = captured.get(0);
        assertEquals("https://example.com/photos/cat-1.jpg", request.getEvidenceUrl(),
                "应取 murl 的原图直链，不能取 src 的缩略图");
        assertEquals(FileUploadEnum.URL_TYPE, request.getUploadType());
        assertEquals("猫1", request.getPicName(), "名称应为「前缀+序号」");
    }

    @Test
    @DisplayName("img 节点没有 murl 时，应从结果条目的 m 属性 JSON 里取原图地址")
    void shouldExtractMurlFromMetaAttribute() throws Exception {
        // 必应较新版本的片段：murl 只出现在 a.iusc 的 m 属性里
        String fragment = "<div class=\"dgControl\">"
                + "  <a class=\"iusc\" m=\"{&quot;murl&quot;:&quot;https://example.com/photos/bird.jpg&quot;,"
                + "&quot;turl&quot;:&quot;https://cn.bing.com/th?id=OIP.zzz&quot;}\">"
                + "    <img class=\"mimg\" src=\"/th?id=OIP.zzz&w=200\">"
                + "  </a>"
                + "</div>";
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(fragment));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        service.uploadEvidenceByBatch(batchRequest("鸟", null, 10), adminUser());

        assertEquals("https://example.com/photos/bird.jpg", captured.get(0).getEvidenceUrl(),
                "应从 m 属性解析出原图地址，避免退回没有后缀的缩略图");
    }

    @Test
    @DisplayName("m 属性不是合法 JSON 时应安全兜底，不能抛异常")
    void shouldSurviveBrokenMetaAttribute() throws Exception {
        String fragment = "<div class=\"dgControl\">"
                + "  <a class=\"iusc\" m=\"{not-valid-json\">"
                + "    <img class=\"mimg\" src=\"/th?id=OIP.zzz\" murl=\"https://example.com/ok.jpg\">"
                + "  </a>"
                + "</div>";
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(fragment));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        service.uploadEvidenceByBatch(batchRequest("鸟", null, 10), adminUser());

        assertEquals(1, captured.size(), "解析失败也应回退到节点自身的 murl");
        assertEquals("https://example.com/ok.jpg", captured.get(0).getEvidenceUrl());
    }

    @Test
    @DisplayName("协议相对地址应补成 https 后传入")
    void shouldFixProtocolRelativeUrl() throws Exception {
        // 只留一个节点，验证 // 开头地址的处理
        String fragment = "<div class=\"dgControl\"><img class=\"mimg\" "
                + "src=\"/th?id=OIP.x\" murl=\"//cdn.example.com/photos/dog-2.png\"></div>";
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(fragment));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        service.uploadEvidenceByBatch(batchRequest("狗", null, 10), adminUser());

        assertEquals("https://cdn.example.com/photos/dog-2.png", captured.get(0).getEvidenceUrl());
    }

    @Test
    @DisplayName("多张影像时序号应连续递增")
    void shouldIncreaseIndexPerImage() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT_MULTI));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        Integer count = service.uploadEvidenceByBatch(batchRequest("猫", null, 10), adminUser());

        assertEquals(2, count);
        assertEquals(2, captured.size());
        assertEquals("猫1", captured.get(0).getPicName());
        assertEquals("猫2", captured.get(1).getPicName(),
                "序号要跟着成功数递增，不能因为排序而乱掉");
    }

    @Test
    @DisplayName("抓取数量应真正限制上传条数")
    void countShouldLimitUploads() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT_MULTI));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        Integer count = service.uploadEvidenceByBatch(batchRequest("猫", null, 1), adminUser());

        assertEquals(1, count, "只要 1 条就不该传 2 次");
        assertEquals(1, captured.size());
    }

    @Test
    @DisplayName("没传 namePrefix 时应退回搜索词作为前缀")
    void shouldFallbackNamePrefixToSearchText() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        service.uploadEvidenceByBatch(batchRequest("风景", "  ", 10), adminUser());

        assertEquals("风景1", captured.get(0).getPicName());
    }

    @Test
    @DisplayName("显式传入的 namePrefix 应被尊重")
    void shouldRespectGivenNamePrefix() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT));
        List<EvidenceUploadRequest> captured = captureSuccessfulUploads(service);

        service.uploadEvidenceByBatch(batchRequest("猫", "我的图", 10), adminUser());

        assertEquals("我的图1", captured.get(0).getPicName());
    }

    @Test
    @DisplayName("搜索词为空应直接报参数错误，不该去抓页面")
    void blankSearchTextShouldFailFast() {
        EvidenceServiceImpl service = new EvidenceServiceImpl();
        EvidenceUploadByBatchRequest request = batchRequest("   ", null, 10);
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> service.uploadEvidenceByBatch(request, adminUser()));
        assertTrue(e.getMessage().contains("搜索词"), e.getMessage());
    }

    @Test
    @DisplayName("抓取数量超出上限应报错")
    void tooLargeCountShouldFail() throws Exception {
        EvidenceServiceImpl service = serviceWithDocument(Jsoup.parse(BING_FRAGMENT));
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> service.uploadEvidenceByBatch(batchRequest("猫", null, 31), adminUser()));
        assertTrue(e.getMessage().contains("30"), e.getMessage());
    }

    /** 构造批量抓取请求 */
    private EvidenceUploadByBatchRequest batchRequest(String searchText, String namePrefix, int count) {
        EvidenceUploadByBatchRequest request = new EvidenceUploadByBatchRequest();
        request.setSearchText(searchText);
        request.setNamePrefix(namePrefix);
        request.setCount(count);
        return request;
    }
}
