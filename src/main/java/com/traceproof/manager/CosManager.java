package com.traceproof.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.traceproof.conifg.CosClientConfig;
import com.traceproof.utils.ColorUtils;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.SetObjectAclRequest;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest =
                new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        PutObjectResult result = cosClient.putObject(putObjectRequest);
        setPublicReadAcl(key);
        return result;
    }

    /**
     * 给对象设置「公有读」权限
     * <p>
     * 存储桶本身是私有读，浏览器直接访问影像 URL 会 403（页面里看到的裂图就是它）。
     * 影像这类静态资源需要匿名可读，所以在每次上传后单独把该对象设为公有读。
     *
     * @param key 对象 key
     */
    public void setPublicReadAcl(String key) {
        try {
            cosClient.setObjectAcl(new SetObjectAclRequest(
                    cosClientConfig.getBucket(), key, CannedAccessControlList.PublicRead));
        } catch (Exception e) {
            // ACL 设置失败只记日志不抛出：文件已经传上去了，主流程不应因此失败
            log.error("设置对象公有读权限失败，key = {}: {}", key, e.getMessage());
        }
    }

    /**
     * 上传对象（附带影像信息，并生成一张压缩图）
     * <p>
     * 一次请求同时产出两份文件：原图和压缩后的 webp，
     * 压缩图放在与原图相同的目录下，只是后缀变成 .webp。
     *
     * @param key  唯一键（原图在对象存储中的路径）
     * @param file 文件
     */
    public PutObjectResult putEvidenceObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        // 对影像进行处理（获取基本信息也被视作为一种影像的处理）
        PicOperations picOperations = new PicOperations();
        // 1 表示返回原图信息
        picOperations.setIsPicInfo(1);
        // 构造处理参数
        picOperations.setRules(buildCompressRules(key));
        putObjectRequest.setPicOperations(picOperations);
        PutObjectResult result = cosClient.putObject(putObjectRequest);
        // 原图和压缩图都要公有读，否则页面上图会裂
        setPublicReadAcl(key);
        String webpKey = buildWebpKey(key);
        if (!webpKey.equals(key)) {
            setPublicReadAcl(webpKey);
        }
        return result;
    }

    /**
     * 构造压缩规则：转成 webp，放在与原图同一个目录下
     * <p>
     * 注意 fileId 必须是完整路径。用 FileUtil.mainName(key) 会把目录部分丢掉，
     * 压缩图会被传到桶根目录，既丢失了按用户分目录的组织，也容易和别的用户撞名。
     *
     * @param key 原图路径，形如 /public/1/2026-10-12_xxx.png
     * @return 影像处理规则列表
     */
    private List<PicOperations.Rule> buildCompressRules(String key) {
        List<PicOperations.Rule> rules = new ArrayList<>();
        // 已经就是 webp 的不用再转一份，省一次存储和流量
        if ("webp".equalsIgnoreCase(FileUtil.getSuffix(key))) {
            return rules;
        }
        String webpKey = buildWebpKey(key);
        PicOperations.Rule compressRule = new PicOperations.Rule();
        compressRule.setRule("imageMogr2/format/webp");
        compressRule.setBucket(cosClientConfig.getBucket());
        compressRule.setFileId(webpKey);
        rules.add(compressRule);
        return rules;
    }

    /**
     * 由原图路径推导压缩图路径
     * <p>
     * 目录保持不变，只把后缀换成 .webp：
     * /public/1/2026-10-12_abc.png -> /public/1/2026-10-12_abc.webp
     *
     * @param key 原图路径
     * @return 压缩图路径
     */
    public static String buildWebpKey(String key) {
        String suffix = FileUtil.getSuffix(key);
        if (suffix.isEmpty()) {
            return key + ".webp";
        }
        // 去掉结尾的 .后缀 再拼 .webp
        return key.substring(0, key.length() - suffix.length() - 1) + ".webp";
    }


    /**
     * 下载对象
     *
     * @param key 唯一键
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest);
    }



    /**
     * 删除对象
     *
     * @param key 文件 key（对象在存储桶中的路径，不是完整访问地址）
     */
    public void deleteObject(String key) throws CosClientException {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }

    /**
     * 由访问地址反推出对象 key
     * <p>
     * deleteObject 需要的是 key（例如 /public/1/2026-10-12_abc.jpg），
     * 而数据库里存的是带域名的完整地址。直接把整条 URL 传进去删不掉任何东西 ——
     * 对象存储删除不存在的 key 并不会报错，所以这种错误会静默发生。
     *
     * @param fileUrl 完整访问地址
     * @return 对象 key，无法解析时返回 null
     */
    public String parseKeyFromUrl(String fileUrl) {
        if (StrUtil.isBlank(fileUrl)) {
            return null;
        }
        // 已经是相对路径（key）时直接返回
        if (!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://")) {
            return fileUrl.startsWith("/") ? fileUrl : "/" + fileUrl;
        }
        try {
            URL url = new URL(fileUrl);
            String path = url.getPath();
            if (StrUtil.isBlank(path)) {
                return null;
            }
            return path.startsWith("/") ? path : "/" + path;
        } catch (MalformedURLException e) {
            log.warn("影像地址无法解析，跳过多余的对象存储操作: {}", fileUrl);
            return null;
        }
    }

    /**
     * 删除影像对应的对象（原图与缩略图）
     * <p>
     * 删除失败只记录日志，不向上抛：此时数据库记录已经删掉了，
     * 让请求失败反而会造成「页面报错但数据已没了」的困惑。
     * 残留的对象文件可以在对象存储控制台清理。
     *
     * @param fileUrl 影像的完整访问地址
     */
    public void deleteObjectByUrl(String fileUrl) {
        String key = parseKeyFromUrl(fileUrl);
        if (key == null) {
            return;
        }
        try {
            deleteObject(key);
            log.info("已删除对象存储文件: {}", key);
        } catch (Exception e) {
            log.error("删除对象存储文件失败，key = {}", key, e);
        }
    }

    /**
     * 获取影像主色调（腾讯云数据万象 imageAve 接口）
     * <p>
     * 在影像可访问地址后追加 {@code ?imageAve}，数据万象会返回该图的平均主色调，
     * 形如 {@code {"RGB":"0x736246"}}。这是「以腾讯云返回的主色调为准」的唯一来源，
     * 不会在本地自行计算。
     *
     * @param imageUrl 影像的可访问地址（通常是对象存储原图地址）
     * @return 规范化的主色调，形如 #736246；获取失败返回 null（不阻断上传）
     */
    public String getMainColor(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        // URL 可能已带查询参数（一般没有），统一用 & 或 ? 追加
        String aveUrl = imageUrl.contains("?") ? imageUrl + "&imageAve" : imageUrl + "?imageAve";
        try {
            String resp = HttpUtil.createGet(aveUrl).timeout(3000).execute().body();
            if (StrUtil.isBlank(resp)) {
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp);
            String rgb = json.getStr("RGB");
            return ColorUtils.normalizeHex(rgb);
        } catch (Exception e) {
            log.warn("获取影像主色调失败（不影响上传），url = {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }




}

