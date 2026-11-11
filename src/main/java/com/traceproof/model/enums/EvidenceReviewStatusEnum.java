package com.traceproof.model.enums;

import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author <a href="https://github.com/lieeew">leikooo</a>
 */
@Getter
public enum EvidenceReviewStatusEnum {  
    REVIEWING("待审核", 0),  
    PASS("通过", 1),  
    REJECT("拒绝", 2);
  
    private final String text;

    private final int value;

    private static final Map<Integer, EvidenceReviewStatusEnum> EVIDENCE_REVIEW_STATUS_ENUM_MAP =
            Arrays.stream(EvidenceReviewStatusEnum.values())
                    .collect(Collectors.toMap(EvidenceReviewStatusEnum::getValue, e -> e));

    EvidenceReviewStatusEnum(String text, int value) {  
        this.text = text;  
        this.value = value;  
    }  
  
    /**  
     * 根据 value 获取枚举
     *
     * @param value 审核状态的取值
     * @return 枚举值，取不到时抛出参数异常
     */  
    public static EvidenceReviewStatusEnum getEnumByValue(Integer value) {
        EvidenceReviewStatusEnum evidenceReviewStatusEnum = value == null ? null : EVIDENCE_REVIEW_STATUS_ENUM_MAP.getOrDefault(value, null);
        ThrowUtils.throwIf(Objects.isNull(evidenceReviewStatusEnum), new BusinessException(ErrorCode.PARAMS_ERROR));
        return evidenceReviewStatusEnum;
    }  
}
