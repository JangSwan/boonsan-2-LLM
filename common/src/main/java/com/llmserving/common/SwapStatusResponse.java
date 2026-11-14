package com.llmserving.common;

/**
 * 교체·롤백 상태 조회 결과 (Model Manager → Web UI AdminConsole).
 *
 * @param state       상태 (예: IDLE, SWAPPING, COMPLETED, ROLLED_BACK)
 * @param activeModel 활성 모델
 * @param activeStage 활성 단계
 * @param targetStage 목표 단계
 * @param ratio       현재 v2 비율 (0.0 ~ 1.0)
 * @param updatedAt   마지막 상태 변경 시각 (epoch ms)
 */
public record SwapStatusResponse(
        String state,
        String activeModel,
        String activeStage,
        String targetStage,
        double ratio,
        long updatedAt) {
}
