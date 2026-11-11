package com.traceproof.manager.uplode;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.traceproof.conifg.CosClientConfig;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.CosManager;
import com.traceproof.model.dto.file.UploadEvidenceResult;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 影像上传模板
 * <p>
 * 用模板方法固定「校验 → 生成对象存储路径 → 落临时文件 → 上传 → 封装结果 → 清理临时文件」这一整套流程，
 * 不同来源（本地文件、URL 下载）只需要实现 {@link #validEvidence}、{@link #getOriginFilename}、
 * {@link #processFile} 三个差异化步骤。
 *
 * @author lsj
 */
@Slf4j
public abstract class EvidenceUploadTemplate {

    /**
     * 单张影像体积上限：2MB
     */
    public static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;

    /**
     * 允许上传的影像后缀
     */
    public static final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpeg", "jpg", "png", "webp");

    /**
     * 允许上传的影像 MIME 类型
     */
    public static final List<String> ALLOW_CONTENT_TYPES =
            Arrays.asList("image/jpeg", "image/jpg", "image/png", "image/webp");

    @Resource
    protected CosManager cosManager;

    @Resource
    protected CosClientConfig cosClientConfig;

    /**
     * 模板方法，定义上传流程（子类不要覆盖）
     *
     * @param inputSource      输入源：MultipartFile 或影像 URL
     * @param uploadPathPrefix 对象存储的路径前缀
     * @return 上传结果
     */
    public final UploadEvidenceResult uploadEvidence(Object inputSource, String uploadPathPrefix) {
        // 1. 校验影像
        validEvidence(inputSource);

        // 2. 生成对象存储路径
        String uuid = RandomUtil.randomString(16);
        String originFilename = getOriginFilename(inputSource);
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid,
                FileUtil.getSuffix(originFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);

        File file = null;
        try {
            // 3. 创建临时文件
            file = File.createTempFile(uploadPath, null);
            // 4. 把输入源落到临时文件（本地文件直接转存，URL 则下载）
            processFile(inputSource, file);

            // 4.1 按真实文件内容修正上传路径（URL 上传的地址里常常没有可信的后缀）
            String realUploadPath = resolveUploadPath(file, uploadPath);

            // 5. 上传到对象存储（同时产出原图与压缩图）
            PutObjectResult putObjectResult = cosManager.putEvidenceObject(realUploadPath, file);
            ImageInfo imageInfo = getImageInfo(putObjectResult, file, realUploadPath);

            // 6. 取出压缩图信息。压缩是可选的：
            //    原图已经是 webp 时不会生成压缩图，此时退回用原图信息
            CIObject compressedCiObject = getCompressedObject(putObjectResult);
            if (compressedCiObject != null) {
                // 尺寸、体积用压缩图的，列表页展示的就是它
                return buildResult(originFilename, realUploadPath, compressedCiObject);
            }
            // 没有压缩图：url 与 thumbnailUrl 都指向原图
            return buildResult(originFilename, file, realUploadPath, imageInfo);
        } catch (BusinessException e) {
            // 业务异常原样抛出，避免被包装成笼统的「上传失败」
            throw e;
        } catch (Exception e) {
            log.error("影像上传到对象存储失败，路径 = {}，临时文件大小 = {} 字节",
                    uploadPath, file == null ? 0 : file.length(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            // 7. 清理临时文件
            deleteTempFile(file);
        }
    }

    /**
     * 取出对象存储生成的压缩图信息
     *
     * @param putObjectResult 上传结果
     * @return 压缩图信息，未生成时返回 null
     */
    private CIObject getCompressedObject(PutObjectResult putObjectResult) {
        CIUploadResult ciUploadResult = putObjectResult.getCiUploadResult();
        if (ciUploadResult == null || ciUploadResult.getProcessResults() == null) {
            return null;
        }
        List<CIObject> objectList = ciUploadResult.getProcessResults().getObjectList();
        if (CollUtil.isEmpty(objectList)) {
            return null;
        }
        return objectList.get(0);
    }

    /**
     * 封装返回结果（使用压缩图作为缩略图）
     * <p>
     * url 保留原始上传路径，thumbnailUrl 指向压缩图，
     * 这样列表页用缩略图、详情页用原图。
     *
     * @param originFilename     原始文件名
     * @param uploadPath         原图在对象存储中的路径
     * @param compressedCiObject 压缩图信息
     * @return 上传结果
     */
    private UploadEvidenceResult buildResult(String originFilename, String uploadPath,
                                            CIObject compressedCiObject) {
        UploadEvidenceResult uploadEvidenceResult = new UploadEvidenceResult();
        // 尺寸与体积都取自压缩图：列表页实际展示的就是它
        int picWidth = compressedCiObject.getWidth();
        int picHeight = compressedCiObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadEvidenceResult.setPicName(FileUtil.mainName(originFilename));
        uploadEvidenceResult.setPicWidth(picWidth);
        uploadEvidenceResult.setPicHeight(picHeight);
        uploadEvidenceResult.setPicScale(picScale);
        uploadEvidenceResult.setPicFormat(compressedCiObject.getFormat());
        uploadEvidenceResult.setPicSize(compressedCiObject.getSize().longValue());
        // 原图地址
        uploadEvidenceResult.setUrl(buildFileUrl(uploadPath));
        // 压缩图地址：优先用对象存储返回的 key，取不到时按约定推导
        String compressedKey = StrUtil.isNotBlank(compressedCiObject.getKey())
                ? compressedCiObject.getKey()
                : CosManager.buildWebpKey(uploadPath);
        uploadEvidenceResult.setThumbnailUrl(buildFileUrl(compressedKey));
        return uploadEvidenceResult;
    }

    /**
     * 把对象存储路径拼成可访问地址
     * <p>
     * uploadPath 以 / 开头，host 末尾不带 /，直接拼接会产生 host//public/...
     * 这种双斜杠。这里统一去掉多余的那一个，避免同一张图出现两种写法
     * （对象存储 key 里带不带前导斜杠都由它自己处理，不影响访问）。
     *
     * @param path 对象存储路径或 key
     * @return 完整的访问地址
     */
    private String buildFileUrl(String path) {
        return cosClientConfig.getHost() + "/" + StrUtil.removePrefix(path, "/");
    }


    /**
     * 校验输入源（本地文件或 URL）
     *
     * @param inputSource 输入源
     */
    protected abstract void validEvidence(Object inputSource);

    /**
     * 按真实文件内容修正上传路径（默认原样返回）
     * <p>
     * 本地文件上传的后缀来自用户选择的文件名，基本可信；
     * URL 上传则经常遇到「后缀不是真后缀」的地址，例如必应缩略图
     * {@code /th/id/OIP-C.TTm1he_rRDKZz4SCHovBpQHaHa?w=155}，
     * 点号后面的那一串是 CDN 的加密串，照着它拼出来的对象存储 key 后缀是错的，
     * 需要下载完按文件头判断真实格式（见 {@link UrlEvidenceUpload}）。
     *
     * @param file       已落地的临时文件
     * @param uploadPath 依据文件名 / URL 算出的上传路径
     * @return 最终使用的上传路径，默认不改
     */
    protected String resolveUploadPath(File file, String uploadPath) {
        return uploadPath;
    }

    /**
     * 获取输入源的原始文件名（含后缀）
     *
     * @param inputSource 输入源
     * @return 原始文件名
     */
    protected abstract String getOriginFilename(Object inputSource);

    /**
     * 处理输入源并生成本地临时文件
     *
     * @param inputSource 输入源
     * @param file        待写入的临时文件
     * @throws Exception 处理失败
     */
    protected abstract void processFile(Object inputSource, File file) throws Exception;

    /* ------------------------------ 子类可复用的公共校验 ------------------------------ */

    /**
     * 校验文件名后缀是否是允许的影像格式
     *
     * @param originFilename 原始文件名
     */
    protected void validFileFormat(String originFilename) {
        ThrowUtils.throwIf(StrUtil.isBlank(originFilename), ErrorCode.PARAMS_ERROR, "文件名不能为空");
        String fileSuffix = FileUtil.getSuffix(originFilename);
        ThrowUtils.throwIf(!ALLOW_FORMAT_LIST.contains(fileSuffix.toLowerCase()),
                ErrorCode.PARAMS_ERROR, "文件类型错误，仅支持 " + String.join(" / ", ALLOW_FORMAT_LIST));
    }

    /**
     * 校验文件体积
     *
     * @param fileSize 文件字节数
     */
    protected void validFileSize(long fileSize) {
        ThrowUtils.throwIf(fileSize > MAX_FILE_SIZE, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
    }

    /**
     * 校验 MIME 类型（仅 URL 上传时能拿到）
     *
     * @param contentType 响应的 Content-Type
     */
    protected void validContentType(String contentType) {
        if (StrUtil.isBlank(contentType)) {
            return;
        }
        // 形如 image/jpeg;charset=utf-8，取分号前的部分
        String mimeType = StrUtil.subBefore(contentType, ";", false).trim().toLowerCase();
        ThrowUtils.throwIf(!ALLOW_CONTENT_TYPES.contains(mimeType),
                ErrorCode.PARAMS_ERROR, "文件类型错误，仅支持 jpg / png / webp 影像");
    }

    /**
     * 带体积保护的流式下载，避免远程文件把磁盘占满
     *
     * @param inputStream 远程输入流
     * @param file        目标临时文件
     * @param maxSize     允许的最大字节数
     * @throws Exception 下载失败或超过体积上限
     */
    protected void downloadToFile(InputStream inputStream, File file, long maxSize) throws Exception {
        long total = 0;
        try (InputStream in = inputStream;
             OutputStream out = Files.newOutputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                total += len;
                // 不能只信 Content-Length，必须边下边校验
                ThrowUtils.throwIf(total > maxSize, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
                out.write(buffer, 0, len);
            }
        }
        ThrowUtils.throwIf(total == 0, ErrorCode.PARAMS_ERROR, "下载到的文件为空");
    }

    /**
     * 删除临时文件
     *
     * @param file 临时文件
     */
    public void deleteTempFile(File file) {
        if (file == null) {
            return;
        }
        boolean deleteResult = file.delete();
        if (!deleteResult) {
            log.error("file delete error, filepath = {}", file.getAbsolutePath());
        }
    }

    /* ------------------------------ 内部方法 ------------------------------ */

    /**
     * 取出对象存储返回的影像信息
     *
     * @param putObjectResult 上传结果
     * @param file            临时文件
     * @param uploadPath      对象存储路径
     * @return 影像信息
     */
    private ImageInfo getImageInfo(PutObjectResult putObjectResult, File file, String uploadPath) {
        if (putObjectResult == null
                || putObjectResult.getCiUploadResult() == null
                || putObjectResult.getCiUploadResult().getOriginalInfo() == null
                || putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo() == null) {
            log.error("对象存储未返回影像信息，文件大小 = {} 字节，路径 = {}", file.length(), uploadPath);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "影像处理失败，请确认文件是有效的影像");
        }
        return putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
    }

    /**
     * 封装返回结果（没有压缩图时，缩略图退回原图）
     *
     * @param originFilename 原始文件名
     * @param file           临时文件
     * @param uploadPath     对象存储路径
     * @param imageInfo      影像信息
     * @return 上传结果
     */
    private UploadEvidenceResult buildResult(String originFilename, File file, String uploadPath,
                                            ImageInfo imageInfo) {
        UploadEvidenceResult uploadEvidenceResult = new UploadEvidenceResult();
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadEvidenceResult.setPicName(FileUtil.mainName(originFilename));
        uploadEvidenceResult.setPicWidth(picWidth);
        uploadEvidenceResult.setPicHeight(picHeight);
        uploadEvidenceResult.setPicScale(picScale);
        uploadEvidenceResult.setPicFormat(imageInfo.getFormat());
        uploadEvidenceResult.setPicSize(FileUtil.size(file));
        String url = buildFileUrl(uploadPath);
        uploadEvidenceResult.setUrl(url);
        // 没有压缩图，缩略图直接用原图，保证前端不必做空判断
        uploadEvidenceResult.setThumbnailUrl(url);
        return uploadEvidenceResult;
    }
}
