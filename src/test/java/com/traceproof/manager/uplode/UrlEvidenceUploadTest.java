package com.traceproof.manager.uplode;

import com.traceproof.conifg.CosClientConfig;
import com.traceproof.exception.BusinessException;
import com.traceproof.manager.CosManager;
import com.traceproof.model.dto.file.UploadEvidenceResult;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL 上传的校验与文件名解析测试
 * <p>
 * 校验逻辑全部在调用对象存储之前完成，因此这里不需要真实的 COS 环境，
 * 起一个本地 HttpServer 当作图源即可覆盖 URL 的各种边界。
 */
class UrlEvidenceUploadTest {

    private static HttpServer server;
    private static String baseUrl;

    /** 一张极小但合法的 PNG（1x1，纯色） */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, 0x60, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x01, (byte) 0xE2, 0x21, 0x71, (byte) 0x8B,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82,
    };

    /**
     * 一张极小但合法的 WebP（RIFF....WEBP 头）
     * <p>
     * 必应缩略图这类地址返回的就是 webp，而地址里没有 .webp 后缀。
     */
    private static final byte[] TINY_WEBP = new byte[]{
            'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00,
            'W', 'E', 'B', 'P', 'V', 'P', '8', ' ',
            0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };

    /** 一张极小但合法的 JPEG（FF D8 FF 头） */
    private static final byte[] TINY_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F',
            'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
    };

    private final UrlEvidenceUpload urlEvidenceUpload = new UrlEvidenceUpload();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // 正常影像
        server.createContext("/good.png", exchange -> respond(exchange, 200, "image/png", TINY_PNG));
        // 非影像类型
        server.createContext("/text.txt", exchange ->
                respond(exchange, 200, "text/plain", "not an image".getBytes(StandardCharsets.UTF_8)));
        // 后缀伪装成 png，但服务端返回 text/plain，用于验证 Content-Type 校验
        server.createContext("/fake.png", exchange ->
                respond(exchange, 200, "text/plain", "not an image".getBytes(StandardCharsets.UTF_8)));
        // 声明 3MB，触发体积校验（注意：这里只校验响应头，不需要真的发 3MB）
        server.createContext("/big.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(3 * 1024 * 1024));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        // 不支持 HEAD 的站点
        server.createContext("/no-head.png", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, "image/png", TINY_PNG);
        });
        // 不存在的影像
        server.createContext("/missing.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        // 必应缩略图那种地址：路径里的「后缀」是 CDN 加密串，实际内容是 webp
        server.createContext("/th/id/OIP-C.TTm1he_rRDKZz4SCHovBpQHaHa", exchange ->
                respond(exchange, 200, "image/webp", TINY_WEBP));
        // 没有后缀、Content-Type 也缺失，只能靠文件头判断：内容是 jpeg
        server.createContext("/no-suffix-no-type", exchange -> {
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(TINY_JPEG.length));
            exchange.sendResponseHeaders(200, TINY_JPEG.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(TINY_JPEG);
            }
        });
        // 没有后缀，内容也不是影像
        server.createContext("/no-suffix-text", exchange -> {
            byte[] body = "not an image".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // 第一次 502、第二次正常：模拟 CDN 偶发失败
        server.createContext("/flaky.png", new com.sun.net.httpserver.HttpHandler() {
            private int count = 0;

            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                count += 1;
                if (count == 1) {
                    exchange.sendResponseHeaders(502, -1);
                    exchange.close();
                    return;
                }
                respond(exchange, 200, "image/png", TINY_PNG);
            }
        });
        // 一直 502
        server.createContext("/always-502.png", exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 统一的响应输出 */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /* ------------------------------ 入参与协议校验 ------------------------------ */

    @Test
    @DisplayName("空地址应被拒绝")
    void blankUrlShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence("   "));
        assertEquals("文件地址不能为空", e.getMessage());
    }

    @Test
    @DisplayName("格式非法的地址应被拒绝")
    void malformedUrlShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence("not-a-url"));
        assertEquals("文件地址格式不正确", e.getMessage());
    }

    @Test
    @DisplayName("非 http/https 协议应被拒绝")
    void nonHttpSchemeShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence("ftp://example.com/a.png"));
        assertTrue(e.getMessage().contains("HTTP"), e.getMessage());
    }

    /* ------------------------------ 类型与体积校验 ------------------------------ */

    @Test
    @DisplayName("URL 后缀不是影像时应被拒绝")
    void wrongSuffixShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence(baseUrl + "/text.txt"));
        assertTrue(e.getMessage().contains("文件类型错误"), e.getMessage());
    }

    @Test
    @DisplayName("Content-Type 不是影像时应被拒绝")
    void wrongContentTypeShouldFail() {
        // 后缀伪装成 png，但服务端返回 text/plain
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence(baseUrl + "/fake.png"));
        assertTrue(e.getMessage().contains("Content-Type") || e.getMessage().contains("影像"),
                e.getMessage());
    }

    @Test
    @DisplayName("Content-Length 超过 2MB 时应被拒绝")
    void tooLargeShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> urlEvidenceUpload.validEvidence(baseUrl + "/big.png"));
        assertEquals("文件大小不能超过 2M", e.getMessage());
    }

    /* ------------------------------ 正常与容错 ------------------------------ */

    @Test
    @DisplayName("合法影像地址应通过校验")
    void goodUrlShouldPass() {
        assertDoesNotThrow(() -> urlEvidenceUpload.validEvidence(baseUrl + "/good.png"));
    }

    @Test
    @DisplayName("影像不存在时不应在 HEAD 阶段直接失败，交给下载阶段报错")
    void missingUrlShouldNotFailAtValidation() {
        // HEAD 返回 404 时选择放行，由下载阶段给出更准确的错误
        assertDoesNotThrow(() -> urlEvidenceUpload.validEvidence(baseUrl + "/missing.png"));
    }

    @Test
    @DisplayName("站点不支持 HEAD 时不应阻塞上传")
    void headNotAllowedShouldPass() {
        assertDoesNotThrow(() -> urlEvidenceUpload.validEvidence(baseUrl + "/no-head.png"));
    }

    /* ------------------------------ 文件名解析 ------------------------------ */

    @Test
    @DisplayName("应从 URL 中解析出带后缀的文件名")
    void shouldExtractFilename() {
        assertEquals("photo.jpg", urlEvidenceUpload.getOriginFilename("https://example.com/a/b/photo.jpg"));
    }

    @Test
    @DisplayName("解析文件名时应忽略 query 参数")
    void shouldIgnoreQueryString() {
        assertEquals("photo.png",
                urlEvidenceUpload.getOriginFilename("https://example.com/a/photo.png?x=1&y=2"));
    }

    @Test
    @DisplayName("解析文件名时应处理百分号编码")
    void shouldDecodeEncodedFilename() {
        assertEquals("图片.png",
                urlEvidenceUpload.getOriginFilename("https://example.com/a/%E5%9B%BE%E7%89%87.png"));
    }

    @Test
    @DisplayName("URL 无文件名时应返回兜底名，保证临时文件前缀合法")
    void shouldFallbackWhenNoFilename() {
        assertEquals("evidence.jpg", urlEvidenceUpload.getOriginFilename("https://example.com/"));
    }

    @Test
    @DisplayName("文件名过短时应返回兜底名")
    void shouldFallbackWhenFilenameTooShort() {
        assertEquals("evidence.jpg", urlEvidenceUpload.getOriginFilename("https://example.com/a"));
    }

    /* ------------------------------ 按文件头修正后缀（CDN 无后缀地址） ------------------------------ */

    @Test
    @DisplayName("必应缩略图地址应通过校验（后缀是 CDN 加密串，不是文件格式）")
    void bingThumbnailUrlShouldPassValidation() {
        // /th/id/OIP-C.TTm1he_rRDKZz4SCHovBpQHaHa 的最后一段点号后面是加密串，
        // 早期实现会把它当后缀并报「文件类型错误」，导致这类合法影像全部传不上去
        assertDoesNotThrow(() -> urlEvidenceUpload.validEvidence(bingThumbnailUrl()));
    }

    @Test
    @DisplayName("后缀是 CDN 加密串时应按文件头把后缀改成真实格式")
    void shouldFixSuffixByFileHeader() throws Exception {
        File webp = tempFileWith(TINY_WEBP);
        try {
            // 伪后缀 OIP-C.TTm1he_xxx -> 真实后缀 webp
            assertEquals("public/1/2026-10-14_abc.webp",
                    urlEvidenceUpload.resolveUploadPath(webp, "public/1/2026-10-14_abc.TTm1he_rRDKZz4SCHovBpQHaHa"));
        } finally {
            webp.delete();
        }
    }

    @Test
    @DisplayName("没有后缀、也没有 Content-Type 时应按文件头识别出 jpg")
    void shouldDetectJpegWithoutSuffix() throws Exception {
        File jpeg = tempFileWith(TINY_JPEG);
        try {
            assertEquals("public/1/evidence.jpg",
                    urlEvidenceUpload.resolveUploadPath(jpeg, "public/1/evidence"));
        } finally {
            jpeg.delete();
        }
    }

    @Test
    @DisplayName("后缀本来就是 jpeg 时不应被改写成 jpg")
    void shouldKeepJpegSuffix() throws Exception {
        File jpeg = tempFileWith(TINY_JPEG);
        try {
            assertEquals("public/1/photo.jpeg",
                    urlEvidenceUpload.resolveUploadPath(jpeg, "public/1/photo.jpeg"));
        } finally {
            jpeg.delete();
        }
    }

    @Test
    @DisplayName("后缀伪装成影像但内容不是影像时应被拒绝")
    void shouldRejectNonImageContent() throws Exception {
        File text = tempFileWith("not an image".getBytes(StandardCharsets.UTF_8));
        try {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> urlEvidenceUpload.resolveUploadPath(text, "public/1/fake.png"));
            assertTrue(e.getMessage().contains("文件类型错误"), e.getMessage());
        } finally {
            text.delete();
        }
    }

    @Test
    @DisplayName("整条链路：无后缀的 webp 地址最终应以 .webp 写入对象存储")
    void shouldUploadWithRealSuffix() throws Exception {
        CosManager cosManager = org.mockito.Mockito.mock(CosManager.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.when(cosManager.putEvidenceObject(keyCaptor.capture(), org.mockito.ArgumentMatchers.any(File.class)))
                .thenReturn(buildResultWithoutCompressed());

        CosClientConfig config = new CosClientConfig();
        config.setHost("https://test-bucket.cos.ap-guangzhou.myqcloud.com");
        setField(urlEvidenceUpload, "cosManager", cosManager);
        setField(urlEvidenceUpload, "cosClientConfig", config);

        UploadEvidenceResult result = urlEvidenceUpload.uploadEvidence(bingThumbnailUrl(), "public/1");

        assertTrue(keyCaptor.getValue().endsWith(".webp"),
                "写入对象存储的 key 后缀不对: " + keyCaptor.getValue());
        assertTrue(result.getUrl().endsWith(".webp"), "返回的原图地址后缀不对: " + result.getUrl());
    }

    /** 必应缩略图风格的地址：路径里没有真实影像后缀 */
    private String bingThumbnailUrl() {
        return baseUrl + "/th/id/OIP-C.TTm1he_rRDKZz4SCHovBpQHaHa?w=155&h=180&c=7&r=0&o=7&pid=1.7&rm=3";
    }

    /* ------------------------------ 下载重试 ------------------------------ */

    @Test
    @DisplayName("首次下载失败（502）时应重试一次并成功")
    void shouldRetryOnceWhenDownloadFails() throws Exception {
        File target = File.createTempFile("download-retry", null);
        try {
            urlEvidenceUpload.processFile(baseUrl + "/flaky.png", target);
            assertArrayEquals(TINY_PNG, java.nio.file.Files.readAllBytes(target.toPath()),
                    "重试后下载到的内容不对");
        } finally {
            target.delete();
        }
    }

    @Test
    @DisplayName("一直失败时应给出明确的下载失败提示")
    void shouldFailAfterRetry() throws Exception {
        File target = File.createTempFile("download-fail", null);
        try {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> urlEvidenceUpload.processFile(baseUrl + "/always-502.png", target));
            assertTrue(e.getMessage().contains("502"), e.getMessage());
        } finally {
            target.delete();
        }
    }

    /** 落一个内容为 bytes 的临时文件 */
    private File tempFileWith(byte[] bytes) throws IOException {
        File file = File.createTempFile("url-upload-test", null);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = EvidenceUploadTemplate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 构造对象存储的返回：只有原图信息，没有压缩图（webp 原图不会再压缩） */
    private PutObjectResult buildResultWithoutCompressed() {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(155);
        imageInfo.setHeight(180);
        imageInfo.setFormat("webp");

        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);

        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);

        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ciUploadResult);
        return result;
    }
}
