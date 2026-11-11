package com.traceproof.manager.uplode;

import com.traceproof.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件上传的校验测试
 * <p>
 * 校验发生在访问对象存储之前，因此不需要真实的 COS 环境。
 */
class FileEvidenceUploadTest {

    private final FileEvidenceUpload fileEvidenceUpload = new FileEvidenceUpload();

    @Test
    @DisplayName("合法影像应通过校验")
    void goodFileShouldPass() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[1024]);
        assertDoesNotThrow(() -> fileEvidenceUpload.validEvidence(file));
    }

    @Test
    @DisplayName("png / webp / jpeg 都应被接受")
    void allAllowedFormatsShouldPass() {
        for (String name : new String[]{"a.png", "a.webp", "a.jpeg", "a.PNG"}) {
            MockMultipartFile file = new MockMultipartFile("file", name, "image/png", new byte[16]);
            assertDoesNotThrow(() -> fileEvidenceUpload.validEvidence(file), name);
        }
    }

    @Test
    @DisplayName("空文件应被拒绝")
    void emptyFileShouldFail() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]);
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence(file));
        assertEquals("文件不能为空", e.getMessage());
    }

    @Test
    @DisplayName("传入 null 应被拒绝")
    void nullFileShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence(null));
        assertEquals("请选择要上传的文件", e.getMessage());
    }

    @Test
    @DisplayName("传入错误类型（例如字符串）应被拒绝")
    void wrongSourceTypeShouldFail() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence("not-a-file"));
        assertEquals("请选择要上传的文件", e.getMessage());
    }

    @Test
    @DisplayName("非影像后缀应被拒绝")
    void wrongSuffixShouldFail() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", new byte[16]);
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence(file));
        assertTrue(e.getMessage().contains("文件类型错误"), e.getMessage());
    }

    @Test
    @DisplayName("没有后缀的文件名应被拒绝")
    void noSuffixShouldFail() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "noextension", "image/jpeg", new byte[16]);
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence(file));
        assertTrue(e.getMessage().contains("文件类型错误"), e.getMessage());
    }

    @Test
    @DisplayName("超过 2MB 应被拒绝")
    void tooLargeShouldFail() {
        byte[] content = new byte[(int) (EvidenceUploadTemplate.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", content);
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileEvidenceUpload.validEvidence(file));
        assertEquals("文件大小不能超过 2M", e.getMessage());
    }

    @Test
    @DisplayName("恰好 2MB 应被接受（边界值）")
    void exactlyMaxSizeShouldPass() {
        byte[] content = new byte[(int) EvidenceUploadTemplate.MAX_FILE_SIZE];
        MockMultipartFile file = new MockMultipartFile("file", "limit.jpg", "image/jpeg", content);
        assertDoesNotThrow(() -> fileEvidenceUpload.validEvidence(file));
    }

    @Test
    @DisplayName("应能正确取出原始文件名")
    void shouldReturnOriginFilename() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "my-photo.png", "image/png", new byte[16]);
        assertEquals("my-photo.png", fileEvidenceUpload.getOriginFilename(file));
    }
}
