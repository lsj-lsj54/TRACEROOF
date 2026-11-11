package com.traceproof.manager;

import com.traceproof.conifg.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 影像上传时生成压缩图规则的测试
 * <p>
 * 关键点：压缩图必须放在与原图相同的目录下。
 * 早期实现用 FileUtil.mainName(key) 拼文件名，会把 /public/{userId}/ 目录整段丢掉，
 * 压缩图被传到桶根目录，既不便于管理也容易与其他用户撞名。
 */
class CosManagerCompressRuleTest {

    /** 构造一个注入了 mock 依赖的 CosManager */
    private CosManager buildManager(COSClient cosClient) throws Exception {
        CosManager cosManager = new CosManager();
        CosClientConfig config = new CosClientConfig();
        config.setBucket("test-bucket-1250000000");
        config.setHost("https://test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com");

        setField(cosManager, "cosClient", cosClient);
        setField(cosManager, "cosClientConfig", config);
        return cosManager;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = CosManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /* ------------------------------ buildWebpKey ------------------------------ */

    @Test
    @DisplayName("压缩图应保留原图的目录结构")
    void shouldKeepDirectoryStructure() {
        String key = "/public/2109210339268718593/2026-10-12_W5ZvwTQl280Zi1sU.jpg";
        assertEquals("/public/2109210339268718593/2026-10-12_W5ZvwTQl280Zi1sU.webp",
                CosManager.buildWebpKey(key));
    }

    @Test
    @DisplayName("多种后缀都应正确替换成 webp")
    void shouldReplaceVariousSuffixes() {
        assertEquals("/a/b/photo.webp", CosManager.buildWebpKey("/a/b/photo.png"));
        assertEquals("/a/b/photo.webp", CosManager.buildWebpKey("/a/b/photo.jpeg"));
        assertEquals("/a/b/photo.webp", CosManager.buildWebpKey("/a/b/photo.JPG"));
        assertEquals("/a/b/photo.webp", CosManager.buildWebpKey("/a/b/photo.webp"));
    }

    @Test
    @DisplayName("没有后缀时应直接追加 .webp")
    void shouldAppendWhenNoSuffix() {
        assertEquals("/a/b/photo.webp", CosManager.buildWebpKey("/a/b/photo"));
    }

    @Test
    @DisplayName("文件名里含点也不应误伤（例如 a.b.jpg）")
    void shouldHandleDotsInName() {
        assertEquals("/a/b/my.photo.webp", CosManager.buildWebpKey("/a/b/my.photo.jpg"));
    }

    /* ------------------------------ 上传时的规则 ------------------------------ */

    @Test
    @DisplayName("上传时应带上压缩规则，且 fileId 与原图同目录")
    void shouldAttachCompressRuleWithSameDirectory() throws Exception {
        COSClient cosClient = mock(COSClient.class);
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());
        CosManager cosManager = buildManager(cosClient);

        File file = File.createTempFile("upload-test", ".jpg");
        try {
            String key = "/public/1/2026-10-12_abc.jpg";
            cosManager.putEvidenceObject(key, file);

            ArgumentCaptor<PutObjectRequest> captor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(cosClient).putObject(captor.capture());
            PutObjectRequest request = captor.getValue();

            PicOperations picOperations = request.getPicOperations();
            assertNotNull(picOperations, "没有设置影像处理参数");
            assertEquals(1, picOperations.getIsPicInfo(), "应要求返回原图信息");

            List<PicOperations.Rule> rules = picOperations.getRules();
            assertNotNull(rules);
            assertEquals(1, rules.size(), "应有一条压缩规则");
            PicOperations.Rule rule = rules.get(0);
            assertEquals("imageMogr2/format/webp", rule.getRule());
            assertEquals("test-bucket-1250000000", rule.getBucket());
            assertEquals("/public/1/2026-10-12_abc.webp", rule.getFileId(),
                    "压缩图 fileId 必须与原图同目录");
            assertTrue(rule.getFileId().startsWith("/public/1/"),
                    "目录结构丢了，压缩图会落到桶根目录");
        } finally {
            file.delete();
        }
    }

    @Test
    @DisplayName("原图已经是 webp 时不再生成压缩图")
    void shouldSkipCompressForWebp() throws Exception {
        COSClient cosClient = mock(COSClient.class);
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());
        CosManager cosManager = buildManager(cosClient);

        File file = File.createTempFile("upload-test", ".webp");
        try {
            cosManager.putEvidenceObject("/public/1/2026-10-12_abc.webp", file);

            ArgumentCaptor<PutObjectRequest> captor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(cosClient).putObject(captor.capture());
            List<PicOperations.Rule> rules = captor.getValue().getPicOperations().getRules();
            assertTrue(rules == null || rules.isEmpty(),
                    "已经是 webp 还转一次，等于白存一份");
        } finally {
            file.delete();
        }
    }

    @Test
    @DisplayName("普通上传不应带影像处理参数")
    void putObjectShouldNotCarryPicOperations() throws Exception {
        COSClient cosClient = mock(COSClient.class);
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());
        CosManager cosManager = buildManager(cosClient);

        File file = File.createTempFile("upload-test", ".bin");
        try {
            cosManager.putObject("/test/a.bin", file);
            ArgumentCaptor<PutObjectRequest> captor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(cosClient).putObject(captor.capture());
            assertEquals(null, captor.getValue().getPicOperations());
        } finally {
            file.delete();
        }
    }
}
