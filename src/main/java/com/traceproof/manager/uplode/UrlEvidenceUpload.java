package com.traceproof.manager.uplode;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.model.enums.FileUploadEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * URL 上传
 * <p>
 * 把远程影像下载后转存到对象存储，适用于「通过影像地址导入」的场景。
 *
 * @author lsj
 */
@Slf4j
@Component(FileUploadEnum.URL_TYPE)
public class UrlEvidenceUpload extends EvidenceUploadTemplate {

    /** URL 探测（HEAD）与下载的超时时间，单位毫秒 */
    private static final int TIMEOUT_MILLIS = 10 * 1000;

    /** 下载失败后的重试次数（0 表示不重试） */
    private static final int DOWNLOAD_RETRY_TIMES = 1;

    /** 最多跟随 3 次重定向，每一次都重新做 SSRF 校验 */
    private static final int MAX_REDIRECT_TIMES = 3;

    /** 仅测试或明确受控的内网环境允许开启；生产默认禁止访问私网地址 */
    @Value("${app.upload.url.allow-private-network:false}")
    private boolean allowPrivateNetwork;

    /**
     * DNS 解析器同时负责安全校验和返回实际连接地址，避免校验后再次发生 DNS rebinding。
     * 连接池保持小规格，重定向由业务层逐级校验，不自动跟随。
     */
    private final CloseableHttpClient httpClient = HttpClients.custom()
            .setDnsResolver(this::resolveAllowedAddresses)
            .setMaxConnTotal(8)
            .setMaxConnPerRoute(4)
            .disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(5000)
                    .setConnectionRequestTimeout(2000)
                    .setSocketTimeout(TIMEOUT_MILLIS)
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build())
            .build();

    /** 无法从 URL 解析出合法文件名时的兜底名，避免 File.createTempFile 前缀过短报错 */
    private static final String FALLBACK_FILENAME = "evidence.jpg";

    /**
     * 常见影像后缀
     * <p>
     * 只有命中这个集合才按后缀提前拦截（例如 .gif / .bmp 直接给出明确报错，
     * 省掉一次网络请求）；不在集合里的「后缀」多半是 CDN 的加密串而非真实格式，
     * 例如必应缩略图 /th/id/OIP-C.TTm1he_rRDKZz4SCHovBpQHaHa，此时不能凭它判断类型，
     * 交给 Content-Type 与文件头校验。
     */
    private static final List<String> KNOWN_IMAGE_SUFFIXES = Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "tif", "tiff", "avif", "heic", "ico");

    /** 读文件头时最多需要 12 个字节（WebP 的 "WEBP" 标志在第 8~11 字节） */
    private static final int HEAD_BYTES = 12;

    /**
     * 校验 URL：合法性、协议、可访问性、类型与体积
     *
     * @param inputSource 影像 URL
     */
    @Override
    protected void validEvidence(Object inputSource) {
        String fileUrl = cast(inputSource);

        // 1. 校验 URL 格式、协议与实际解析地址，拦截 SSRF
        validateRemoteUrl(fileUrl);

        // 2. URL 自带「真后缀」时先按后缀拦一道，省掉一次网络请求。
        //    后缀不在常见影像后缀里的（CDN 加密串、无后缀）不能在这里判死，
        //    否则像必应缩略图那种合法影像会被误杀，改由后面的内容校验负责。
        String filenameFromUrl = tryGetFilenameFromUrl(fileUrl);
        String suffixFromUrl = StrUtil.isBlank(filenameFromUrl) ? null : FileUtil.getSuffix(filenameFromUrl);
        if (StrUtil.isNotBlank(suffixFromUrl)
                && KNOWN_IMAGE_SUFFIXES.contains(suffixFromUrl.toLowerCase())) {
            validFileFormat(filenameFromUrl);
        }

        // 3. HEAD 探测类型与体积
        CloseableHttpResponse response = null;
        try {
            response = executeWithRedirects(fileUrl, true);
            // 部分站点不支持 HEAD，拿不到信息就交给下载后的校验兜底，不在这里直接失败
            int status = response.getStatusLine().getStatusCode();
            if (status != HttpStatus.SC_OK) {
                log.warn("HEAD 探测未返回 200，状态码 = {}，地址 = {}", status, fileUrl);
                return;
            }
            validContentType(header(response, "Content-Type"));
            String contentLength = header(response, "Content-Length");
            if (StrUtil.isNotBlank(contentLength)) {
                try {
                    validFileSize(Long.parseLong(contentLength.trim()));
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小格式错误");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HEAD 探测失败，将直接尝试下载，地址 = {}，原因 = {}", fileUrl, e.getMessage());
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    log.debug("关闭 HEAD 响应失败：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 按真实文件内容修正后缀
     * <p>
     * 下载完成后以文件头为准：既能把 CDN 地址里的伪后缀换成真实格式，
     * 也能挡住「后缀是 .png、内容却不是影像」的地址。
     *
     * @param file       已下载好的临时文件
     * @param uploadPath 依据 URL 算出的上传路径
     * @return 后缀正确的上传路径
     */
    @Override
    protected String resolveUploadPath(File file, String uploadPath) {
        String realSuffix = sniffImageSuffix(file);
        ThrowUtils.throwIf(realSuffix == null, ErrorCode.PARAMS_ERROR,
                "文件类型错误，仅支持 jpg / jpeg / png / webp 影像");
        String currentSuffix = FileUtil.getSuffix(uploadPath);
        // jpg 与 jpeg 是同一种格式的两种写法，地址里写 jpeg 就不必改
        boolean sameFormat = realSuffix.equalsIgnoreCase(currentSuffix)
                || ("jpg".equals(realSuffix) && "jpeg".equalsIgnoreCase(currentSuffix));
        if (sameFormat) {
            return uploadPath;
        }
        // 只替换最后一段文件名里的后缀，目录不动
        int dotIndex = uploadPath.lastIndexOf('.');
        String pathWithoutSuffix = dotIndex > uploadPath.lastIndexOf('/')
                ? uploadPath.substring(0, dotIndex) : uploadPath;
        String fixedPath = pathWithoutSuffix + "." + realSuffix;
        log.info("URL 上传的文件后缀已按文件头修正：{} -> {}", uploadPath, fixedPath);
        return fixedPath;
    }

    /**
     * 读文件头判断真实影像格式
     *
     * @param file 临时文件
     * @return jpg / png / webp，识别不出（不是受支持的影像）返回 null
     */
    private String sniffImageSuffix(File file) {
        byte[] head = new byte[HEAD_BYTES];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (IOException e) {
            log.error("读取文件头失败，文件 = {}", file.getAbsolutePath(), e);
            return null;
        }
        if (read < 4) {
            return null;
        }
        // JPEG：FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG：89 50 4E 47
        if ((head[0] & 0xFF) == 0x89 && (head[1] & 0xFF) == 0x50
                && (head[2] & 0xFF) == 0x4E && (head[3] & 0xFF) == 0x47) {
            return "png";
        }
        // WebP：RIFF....WEBP
        if (read >= HEAD_BYTES
                && (head[0] & 0xFF) == 0x52 && (head[1] & 0xFF) == 0x49
                && (head[2] & 0xFF) == 0x46 && (head[3] & 0xFF) == 0x46
                && (head[8] & 0xFF) == 0x57 && (head[9] & 0xFF) == 0x45
                && (head[10] & 0xFF) == 0x42 && (head[11] & 0xFF) == 0x50) {
            return "webp";
        }
        return null;
    }

    /**
     * 从 URL 中提取文件名（含后缀），query 参数会被忽略
     *
     * @param inputSource 影像 URL
     * @return 文件名，解析不出时返回兜底名
     */
    @Override
    protected String getOriginFilename(Object inputSource) {
        String filename = tryGetFilenameFromUrl(cast(inputSource));
        if (StrUtil.isBlank(filename)) {
            return FALLBACK_FILENAME;
        }
        // File.createTempFile 要求前缀至少 3 个字符，过短时补上兜底名
        return filename.length() < 3 ? FALLBACK_FILENAME : filename;
    }

    /**
     * 下载远程影像到临时文件
     * <p>
     * 站点偶发 5xx（必应缩略图 CDN 实测会偶发 502）时重试一次，
     * 不然用户看到的就是「偶尔传不上去」，很难判断是自己的问题还是对方的问题。
     *
     * @param inputSource 影像 URL
     * @param file        临时文件
     * @throws Exception 下载失败或超出体积上限
     */
    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        String fileUrl = cast(inputSource);
        for (int attempt = 0; ; attempt++) {
            CloseableHttpResponse response = null;
            try {
                response = executeWithRedirects(fileUrl, false);
                int status = response.getStatusLine().getStatusCode();
                if (status == HttpStatus.SC_OK && response.getEntity() != null) {
                    // 边下边校验体积，不能只信任 Content-Length（可能缺省或造假）
                    downloadToFile(response.getEntity().getContent(), file, MAX_FILE_SIZE);
                    return;
                }
                if (attempt >= DOWNLOAD_RETRY_TIMES) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "下载影像失败，状态码 " + status);
                }
                log.warn("下载影像返回 {}，重试一次，地址 = {}", status, fileUrl);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
    }

    /**
     * 执行 HEAD/GET 并手工处理重定向。
     * 不使用客户端自动跳转，否则公网地址可以跳到 127.0.0.1 或云元数据地址。
     */
    private CloseableHttpResponse executeWithRedirects(String initialUrl, boolean head) throws IOException {
        String currentUrl = initialUrl;
        for (int redirectCount = 0; ; redirectCount++) {
            validateRemoteUrl(currentUrl);
            HttpUriRequest request = head ? new HttpHead(currentUrl) : new HttpGet(currentUrl);
            CloseableHttpResponse response = httpClient.execute(request);
            int status = response.getStatusLine().getStatusCode();
            boolean redirect = status == HttpStatus.SC_MOVED_PERMANENTLY
                    || status == HttpStatus.SC_MOVED_TEMPORARILY
                    || status == HttpStatus.SC_SEE_OTHER
                    || status == HttpStatus.SC_TEMPORARY_REDIRECT
                    || status == 308;
            String location = header(response, "Location");
            if (!redirect || StrUtil.isBlank(location)) {
                return response;
            }
            if (redirectCount >= MAX_REDIRECT_TIMES) {
                response.close();
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址重定向次数过多");
            }
            try {
                currentUrl = new URL(new URL(currentUrl), location).toExternalForm();
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件重定向地址格式不正确");
            } finally {
                response.close();
            }
        }
    }

    private static String header(CloseableHttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    /** 校验协议、用户信息、主机名和全部 DNS 解析结果。 */
    private void validateRemoteUrl(String fileUrl) {
        try {
            URL url = new URL(fileUrl);
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            ThrowUtils.throwIf(!Arrays.asList("http", "https").contains(protocol),
                    ErrorCode.PARAMS_ERROR, "仅支持 HTTP 或 HTTPS 协议的文件地址");
            ThrowUtils.throwIf(StrUtil.isBlank(url.getHost()),
                    ErrorCode.PARAMS_ERROR, "文件地址缺少有效主机名");
            ThrowUtils.throwIf(StrUtil.isNotBlank(url.getUserInfo()),
                    ErrorCode.PARAMS_ERROR, "文件地址不允许携带账号或密码");
            if (allowPrivateNetwork) {
                return;
            }
            resolveAllowedAddresses(url.getHost());
        } catch (BusinessException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址无法解析");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址格式不正确");
        }
    }

    /**
     * Apache HttpClient 建连时调用这里，返回的地址会直接用于实际 socket 连接。
     * 即使攻击者在两次 DNS 查询之间切换记录，也只能连接到本次通过校验的地址。
     */
    private InetAddress[] resolveAllowedAddresses(String host) throws UnknownHostException {
        String asciiHost = IDN.toASCII(host);
        InetAddress[] addresses = InetAddress.getAllByName(asciiHost);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        if (!allowPrivateNetwork) {
            for (InetAddress address : addresses) {
                ThrowUtils.throwIf(isBlockedAddress(address),
                        ErrorCode.PARAMS_ERROR, "文件地址不允许访问内网、回环或保留地址");
            }
        }
        return addresses;
    }

    /** 判断是否属于不应由公网导入功能访问的地址。 */
    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes, 0);
        }
        if (bytes.length != 16) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7
        if ((unsigned(bytes[0]) & 0xFE) == 0xFC) {
            return true;
        }
        // IPv4-mapped IPv6：::ffff:a.b.c.d
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            mapped &= bytes[i] == 0;
        }
        mapped &= unsigned(bytes[10]) == 0xFF && unsigned(bytes[11]) == 0xFF;
        if (mapped) {
            return isBlockedIpv4(bytes, 12);
        }
        // NAT64：64:ff9b::/96，内嵌 IPv4 仍需按 IPv4 规则检查。
        if (unsigned(bytes[0]) == 0x00 && unsigned(bytes[1]) == 0x64
                && unsigned(bytes[2]) == 0xFF && unsigned(bytes[3]) == 0x9B) {
            boolean nat64 = true;
            for (int i = 4; i < 12; i++) {
                nat64 &= bytes[i] == 0;
            }
            return nat64 && isBlockedIpv4(bytes, 12);
        }
        // 6to4：2002::/16，内嵌 IPv4 位于第 3~6 字节。
        if (unsigned(bytes[0]) == 0x20 && unsigned(bytes[1]) == 0x02) {
            return isBlockedIpv4(bytes, 2);
        }
        return false;
    }

    /** IPv4 特殊网段；除常见私网外，也覆盖 CGNAT、基准测试、文档和保留地址。 */
    private static boolean isBlockedIpv4(byte[] bytes, int offset) {
        int first = unsigned(bytes[offset]);
        int second = unsigned(bytes[offset + 1]);
        int third = unsigned(bytes[offset + 2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;
        }
        if (first == 198 && second == 51 && third == 100) {
            return true;
        }
        return first == 203 && second == 0 && third == 113;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    @PreDestroy
    public void closeHttpClient() {
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("关闭 URL 上传 HTTP 客户端失败：{}", e.getMessage());
        }
    }

    /**
     * 校验输入源类型
     *
     * @param inputSource 输入源
     * @return 影像 URL
     */
    private String cast(Object inputSource) {
        ThrowUtils.throwIf(!(inputSource instanceof String), ErrorCode.PARAMS_ERROR, "请提供影像地址");
        String fileUrl = ((String) inputSource).trim();
        ThrowUtils.throwIf(StrUtil.isBlank(fileUrl), ErrorCode.PARAMS_ERROR, "文件地址不能为空");
        return fileUrl;
    }

    /**
     * 尽力从 URL 里解析出文件名
     *
     * @param fileUrl 影像 URL
     * @return 文件名，解析不出返回 null
     */
    private String tryGetFilenameFromUrl(String fileUrl) {
        try {
            String path = new URL(fileUrl).getPath();
            if (StrUtil.isBlank(path)) {
                return null;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (StrUtil.isBlank(name)) {
                return null;
            }
            // 处理文件名里的百分号编码，例如 %E5%9B%BE.png
            return URLUtil.decode(name);
        } catch (Exception e) {
            return null;
        }
    }
}
