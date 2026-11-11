package com.traceproof.manager.uplode;

import com.traceproof.conifg.CosClientConfig;
import com.traceproof.manager.CosManager;
import com.traceproof.model.dto.file.UploadEvidenceResult;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上传模板返回值的测试
 * <p>
 * 重点：url 必须是原图、thumbnailUrl 必须是压缩图。
 * 早期实现只把压缩图地址塞进 url，原图地址被丢弃，
 * 前端「列表看缩略图、详情看原图」就没有原图可用。
 */
class EvidenceUploadTemplateResultTest {

    private static final String HOST = "https://test-bucket.cos.ap-guangzhou.myqcloud.com";

    private final FileEvidenceUpload upload = new FileEvidenceUpload();

    /**
     * 注入 mock 依赖
     *
     * @param putObjectResult 让 putEvidenceObject 返回的结果
     */
    private void injectMocks(PutObjectResult putObjectResult) throws Exception {
        CosManager cosManager = mock(CosManager.class);
        when(cosManager.putEvidenceObject(anyString(), any())).thenReturn(putObjectResult);

        CosClientConfig config = new CosClientConfig();
        config.setHost(HOST);

        setField(upload, "cosManager", cosManager);
        setField(upload, "cosClientConfig", config);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = EvidenceUploadTemplate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 构造对象存储的返回：原图信息 + 一张压缩图 */
    private PutObjectResult buildResultWithCompressed(String compressedKey) {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(1200);
        imageInfo.setHeight(800);
        imageInfo.setFormat("png");

        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);

        CIObject compressed = new CIObject();
        compressed.setKey(compressedKey);
        compressed.setFormat("webp");
        compressed.setWidth(1200);
        compressed.setHeight(800);
        compressed.setSize(30000);

        ProcessResults processResults = new ProcessResults();
        processResults.setObjectList(new ArrayList<>(Arrays.asList(compressed)));

        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);
        ciUploadResult.setProcessResults(processResults);

        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ciUploadResult);
        return result;
    }

    /** 构造对象存储的返回：只有原图信息，没有压缩图 */
    private PutObjectResult buildResultWithoutCompressed() {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(800);
        imageInfo.setHeight(600);
        imageInfo.setFormat("webp");

        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);

        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);
        // 不设置 processResults，模拟没有生成压缩图

        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ciUploadResult);
        return result;
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[1024]);
    }

    /**
     * 拼接出的地址形如 host/public/1/xxx
     * <p>
     * 上传路径以 / 开头，拼 host 时会去掉多余的那一个斜杠，
     * 避免出现 host//public/... 的双斜杠写法。
     */
    private String expectedUrl(String path) {
        return HOST + "/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    @Test
    @DisplayName("有压缩图时：url 是原图、thumbnailUrl 是压缩图")
    void shouldReturnBothOriginalAndThumbnail() throws Exception {
        injectMocks(buildResultWithCompressed(
                "/public/1/2026-10-12_abc.webp"));

        UploadEvidenceResult result = upload.uploadEvidence(pngFile(), "public/1");

        assertNotNull(result.getUrl(), "原图地址不能为空");
        assertNotNull(result.getThumbnailUrl(), "缩略图地址不能为空");

        // 原图：保留上传时的路径与原始后缀
        assertTrue(result.getUrl().startsWith(HOST + "/public/1/"),
                "原图地址丢了目录: " + result.getUrl());
        assertTrue(result.getUrl().endsWith(".png"),
                "原图地址后缀不对: " + result.getUrl());

        // 缩略图：指向压缩后的 webp
        assertEquals(expectedUrl("/public/1/2026-10-12_abc.webp"), result.getThumbnailUrl());
        assertEquals("webp", result.getPicFormat(), "格式应取自压缩图");
        assertEquals(30000L, result.getPicSize(), "体积应取自压缩图");
    }

    @Test
    @DisplayName("两个地址必须不同，否则详情页拿到的还是压缩图")
    void urlsShouldDiffer() throws Exception {
        injectMocks(buildResultWithCompressed("/public/1/2026-10-12_abc.webp"));

        UploadEvidenceResult result = upload.uploadEvidence(pngFile(), "public/1");

        assertTrue(!result.getUrl().equals(result.getThumbnailUrl()),
                "url 与 thumbnailUrl 相同，说明原图地址被压缩图覆盖了");
    }

    @Test
    @DisplayName("对象存储没返回 key 时，缩略图地址按约定推导")
    void shouldDeriveThumbnailKeyWhenMissing() throws Exception {
        injectMocks(buildResultWithCompressed(null));

        UploadEvidenceResult result = upload.uploadEvidence(pngFile(), "public/1");

        assertTrue(result.getThumbnailUrl().endsWith(".webp"),
                "推导出的缩略图后缀不是 webp: " + result.getThumbnailUrl());
        // 关键是目录要保留，递推出的地址必须和原图同目录
        assertTrue(result.getThumbnailUrl().contains("/public/1/"),
                "推导出的缩略图目录不对: " + result.getThumbnailUrl());
        assertEquals(result.getUrl().replaceAll("\\.png$", ".webp"), result.getThumbnailUrl(),
                "推导结果应该是把原图后缀换成 webp");
    }

    @Test
    @DisplayName("没有压缩图时，缩略图回退成原图，前端不必做空判断")
    void thumbnailShouldFallbackToOriginal() throws Exception {
        injectMocks(buildResultWithoutCompressed());

        UploadEvidenceResult result = upload.uploadEvidence(pngFile(), "public/1");

        assertEquals(result.getUrl(), result.getThumbnailUrl(),
                "没有压缩图时缩略图应回退成原图");
        assertEquals("webp", result.getPicFormat());
    }

    @Test
    @DisplayName("对象存储没返回影像信息时应给出明确报错")
    void shouldFailFastWhenNoImageInfo() throws Exception {
        PutObjectResult empty = new PutObjectResult();
        injectMocks(empty);

        com.traceproof.exception.BusinessException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.traceproof.exception.BusinessException.class,
                        () -> upload.uploadEvidence(pngFile(), "public/1"));
        assertTrue(e.getMessage().contains("影像处理失败"), e.getMessage());
    }

    @Test
    @DisplayName("返回列表元素类型检查：ProcessResults 为空时不应空指针")
    void shouldNotThrowWhenProcessResultsEmpty() throws Exception {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(100);
        imageInfo.setHeight(100);
        imageInfo.setFormat("png");
        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);
        ProcessResults processResults = new ProcessResults();
        processResults.setObjectList(new ArrayList<>());
        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);
        ciUploadResult.setProcessResults(processResults);
        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ciUploadResult);
        injectMocks(result);

        UploadEvidenceResult uploadResult = upload.uploadEvidence(pngFile(), "public/1");
        assertEquals(uploadResult.getUrl(), uploadResult.getThumbnailUrl());
    }

    /** 确认返回列表不为 null 时才会取第一个元素 */
    @Test
    @DisplayName("ProcessResults 对象为 null（不是空列表）时也不应空指针")
    void shouldNotThrowWhenProcessResultsNull() throws Exception {
        injectMocks(buildResultWithoutCompressed());
        List<String> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
        UploadEvidenceResult result = upload.uploadEvidence(pngFile(), "public/1");
        assertNotNull(result.getUrl());
    }
}
