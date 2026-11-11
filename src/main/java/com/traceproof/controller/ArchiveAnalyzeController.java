package com.traceproof.controller;

import com.traceproof.annotation.AuthCheck;
import com.traceproof.common.BaseResponse;
import com.traceproof.common.ResultUtils;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.model.constant.UserConstant;
import com.traceproof.model.dto.archive.ArchiveAnalyzeRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.archive.ArchiveCategoryAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveRankAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveSizeAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTagAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTrendAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveUsageAnalyzeVO;
import com.traceproof.service.ArchiveAnalyzeService;
import com.traceproof.service.UserService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 档案库分析接口
 * <p>
 * 面向两个场景（见需求文档）：
 * 1）用户档案库分析：传 archiveId 分析自己的档案库（资源用量、分类、标签、大小、上传趋势）；
 * 2）管理员全档案库分析：archiveId 不传时分析公共案例库 + 全部档案库的汇总数据，
 *    并提供档案库使用排行；汇总与排行仅管理员可用，服务层会再校验一遍。
 *
 * @author lsj
 */
@Slf4j
@RestController
@RequestMapping("/archive/analyze")
@AllArgsConstructor
public class ArchiveAnalyzeController {

    private final ArchiveAnalyzeService archiveAnalyzeService;

    private final UserService userService;

    /**
     * 档案库资源使用分析（仪表盘：容量与数量的已用/配额比例）
     */
    @PostMapping("/usage")
    public BaseResponse<ArchiveUsageAnalyzeVO> usageAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                          HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.usage(analyzeRequest, loginUser));
    }

    /**
     * 档案库影像分类分析（分组条形图：各分类数量 + 总大小）
     */
    @PostMapping("/category")
    public BaseResponse<List<ArchiveCategoryAnalyzeVO>> categoryAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                                      HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.category(analyzeRequest, loginUser));
    }

    /**
     * 档案库影像标签分析（词云图：标签关联影像数）
     */
    @PostMapping("/tag")
    public BaseResponse<List<ArchiveTagAnalyzeVO>> tagAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                            HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.tag(analyzeRequest, loginUser));
    }

    /**
     * 档案库影像大小分析（饼图：< 100 KB / 100 KB-1 MB / > 1 MB）
     */
    @PostMapping("/size")
    public BaseResponse<List<ArchiveSizeAnalyzeVO>> sizeAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                              HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.size(analyzeRequest, loginUser));
    }

    /**
     * 上传行为分析（折线图：按天 / 周 / 月的上传数量趋势）
     */
    @PostMapping("/trend")
    public BaseResponse<List<ArchiveTrendAnalyzeVO>> trendAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                                HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.trend(analyzeRequest, loginUser));
    }

    /**
     * 档案库使用排行（柱状图：按已用容量排序的前 N 个档案库，仅管理员）
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/rank")
    public BaseResponse<List<ArchiveRankAnalyzeVO>> rankAnalyze(@RequestBody ArchiveAnalyzeRequest analyzeRequest,
                                                              HttpServletRequest request) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveAnalyzeService.rank(analyzeRequest, loginUser));
    }
}
