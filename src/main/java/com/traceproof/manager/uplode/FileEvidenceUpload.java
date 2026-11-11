package com.traceproof.manager.uplode;

import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.model.enums.FileUploadEnum;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/**
 * 本地文件上传
 * <p>
 * 对应前端直接选择文件上传的方式。
 *
 * @author lsj
 */
@Component(FileUploadEnum.FILE_TYPE)
public class FileEvidenceUpload extends EvidenceUploadTemplate {

    /**
     * 校验本地文件：非空、体积、后缀
     *
     * @param inputSource MultipartFile
     */
    @Override
    protected void validEvidence(Object inputSource) {
        MultipartFile multipartFile = cast(inputSource);
        ThrowUtils.throwIf(multipartFile.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        // 用原始文件名判断后缀，避免拿到 null 时空指针
        validFileFormat(multipartFile.getOriginalFilename());
        validFileSize(multipartFile.getSize());
    }

    /**
     * 获取原始文件名
     *
     * @param inputSource MultipartFile
     * @return 原始文件名
     */
    @Override
    protected String getOriginFilename(Object inputSource) {
        return cast(inputSource).getOriginalFilename();
    }

    /**
     * 把上传的文件转存到临时文件
     *
     * @param inputSource MultipartFile
     * @param file        临时文件
     * @throws Exception 转存失败
     */
    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        cast(inputSource).transferTo(file);
    }

    /**
     * 校验输入源类型
     *
     * @param inputSource 输入源
     * @return MultipartFile
     */
    private MultipartFile cast(Object inputSource) {
        ThrowUtils.throwIf(!(inputSource instanceof MultipartFile), ErrorCode.PARAMS_ERROR, "请选择要上传的文件");
        return (MultipartFile) inputSource;
    }
}
