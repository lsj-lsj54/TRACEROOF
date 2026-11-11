package com.traceproof.manager;

import com.traceproof.conifg.CosClientConfig;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 删除对象存储文件的测试
 * <p>
 * 核心：deleteObject 需要的是 key（对象路径），不是带域名的完整地址。
 * 早期实现直接把 url 传进去，删的是名叫 "https://..." 的对象，
 * 真实文件纹丝不动，而且对象存储删除不存在的 key 不报错，属于静默失败。
 */
class CosManagerDeleteTest {

    private static final String HOST = "https://test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com";
    private static final String BUCKET = "test-bucket-1250000000";

    private COSClient cosClient;
    private CosManager cosManager;

    private CosManager buildManager() throws Exception {
        cosClient = mock(COSClient.class);
        cosManager = new CosManager();

        CosClientConfig config = new CosClientConfig();
        config.setBucket(BUCKET);
        config.setHost(HOST);

        setField(cosManager, "cosClient", cosClient);
        setField(cosManager, "cosClientConfig", config);
        return cosManager;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = CosManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /* ------------------------------ 从 URL 反推 key ------------------------------ */

    @Test
    @DisplayName("应从完整地址反推出对象 key")
    void shouldParseKeyFromUrl() throws Exception {
        CosManager manager = buildManager();
        assertEquals("/public/1/2026-10-12_abc.jpg",
                manager.parseKeyFromUrl(HOST + "/public/1/2026-10-12_abc.jpg"));
    }

    @Test
    @DisplayName("地址里带 query 参数时也不应把参数算进 key")
    void shouldIgnoreQueryString() throws Exception {
        CosManager manager = buildManager();
        assertEquals("/public/1/abc.webp",
                manager.parseKeyFromUrl(HOST + "/public/1/abc.webp?x=1&y=2"));
    }

    @Test
    @DisplayName("本身就是 key 时应原样返回并补上前导斜杠")
    void shouldAcceptPlainKey() throws Exception {
        CosManager manager = buildManager();
        assertEquals("/public/1/abc.jpg", manager.parseKeyFromUrl("/public/1/abc.jpg"));
        assertEquals("/public/1/abc.jpg", manager.parseKeyFromUrl("public/1/abc.jpg"));
    }

    @Test
    @DisplayName("空值或非法地址应返回 null，而不是抛异常")
    void shouldReturnNullForInvalidInput() throws Exception {
        CosManager manager = buildManager();
        assertNull(manager.parseKeyFromUrl(null));
        assertNull(manager.parseKeyFromUrl(""));
        assertNull(manager.parseKeyFromUrl("   "));
    }

    /* ------------------------------ 按地址删除 ------------------------------ */

    @Test
    @DisplayName("按地址删除时应传 key 给对象存储（不能传整条 URL）")
    void shouldDeleteByKeyNotUrl() throws Exception {
        CosManager manager = buildManager();
        manager.deleteObjectByUrl(HOST + "/public/1/2026-10-12_abc.jpg");

        // 断言删除的是 key，而不是完整地址
        verify(cosClient).deleteObject(BUCKET, "/public/1/2026-10-12_abc.jpg");
        verify(cosClient, never()).deleteObject(BUCKET, HOST + "/public/1/2026-10-12_abc.jpg");
    }

    @Test
    @DisplayName("地址非法时不应调用对象存储")
    void shouldNotCallCosWhenKeyUnparsable() throws Exception {
        CosManager manager = buildManager();
        manager.deleteObjectByUrl("");

        verify(cosClient, never()).deleteObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("对象存储删除失败时应被吞掉，不向上抛")
    void shouldSwallowDeleteFailure() throws Exception {
        CosManager manager = buildManager();
        org.mockito.Mockito.doThrow(new RuntimeException("模拟对象存储不可用"))
                .when(cosClient).deleteObject(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        // 不抛异常即通过：数据库记录已删，不该因为清理对象存储失败而让请求失败
        manager.deleteObjectByUrl(HOST + "/public/1/abc.jpg");
    }
}
