package com.traceproof.manager.factory;

import com.traceproof.exception.BusinessException;
import com.traceproof.manager.uplode.FileEvidenceUpload;
import com.traceproof.manager.uplode.EvidenceUploadTemplate;
import com.traceproof.manager.uplode.UrlEvidenceUpload;
import com.traceproof.model.enums.FileUploadEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 上传工厂测试
 * <p>
 * 重点验证：实现类用 @Component("xxx") 标注时，工厂能正确解析出 bean 名称并完成注册。
 * 这一点很容易踩坑——直接用 getAnnotation 取不到 @Service 这类元注解。
 */
class UploadFactoryTest {

    /**
     * 构造一个已完成初始化的工厂
     *
     * @param templates 上传实现列表
     * @return 工厂实例
     */
    private UploadFactory buildFactory(List<EvidenceUploadTemplate> templates) throws Exception {
        UploadFactory factory = new UploadFactory();
        Field field = UploadFactory.class.getDeclaredField("uploadEvidenceTemplates");
        field.setAccessible(true);
        field.set(factory, templates);
        // init 是 private 的，这里直接调用以模拟 @PostConstruct
        java.lang.reflect.Method init = UploadFactory.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(factory);
        return factory;
    }

    @Test
    @DisplayName("file 与 url 两个实现都应注册成功")
    void shouldRegisterBothTemplates() throws Exception {
        FileEvidenceUpload fileUpload = new FileEvidenceUpload();
        UrlEvidenceUpload urlUpload = new UrlEvidenceUpload();
        UploadFactory factory = buildFactory(Arrays.asList(fileUpload, urlUpload));

        // 用枚举常量查询，顺带验证 @Component 注解上的常量确实等于 "file" / "url"
        assertSame(fileUpload, factory.getUploadTemplate(FileUploadEnum.FILE_TYPE));
        assertSame(urlUpload, factory.getUploadTemplate(FileUploadEnum.URL_TYPE));
        // 字面量也要能查到，保证前后端约定的字符串一致
        assertSame(fileUpload, factory.getUploadTemplate("file"));
        assertSame(urlUpload, factory.getUploadTemplate("url"));
    }

    @Test
    @DisplayName("类型不存在时应抛出明确的业务异常")
    void shouldThrowWhenTypeNotFound() throws Exception {
        UploadFactory factory = buildFactory(Arrays.asList(new FileEvidenceUpload()));
        BusinessException e = assertThrows(BusinessException.class,
                () -> factory.getUploadTemplate("unknown"));
        assertEquals(40400, e.getCode());
    }

    @Test
    @DisplayName("旧方法名 getUploadFactory 应继续可用")
    void deprecatedMethodShouldStillWork() throws Exception {
        FileEvidenceUpload fileUpload = new FileEvidenceUpload();
        UploadFactory factory = buildFactory(Arrays.asList(fileUpload));
        assertNotNull(factory.getUploadFactory(FileUploadEnum.FILE_TYPE));
    }
}
