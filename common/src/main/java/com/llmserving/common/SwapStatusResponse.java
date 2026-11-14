package com.llmserving.common;

/**
 * 교체 상태 조회 응답 (AdminConsole.showSwapStatus → ModelSwapController.getSwapStatus).
 *
 * @param status      교체 상태 (model-manager가 정한다. 예: IDLE, SWAPPING, COMPLETED, ROLLED_BACK)
 * @param activeModel 활성 모델 이름
 * @param activeStage 활성 단계 이름
 * @param targetStage 목표 단계 이름 (교체 중이 아니면 null)
 * @param ratio       현재 v2 비율
 * @param updatedAt   상태 변경 시각 (epoch 밀리초)
 */
public record SwapStatusResponse(
        String status,
        String activeModel,
        String activeStage,
        String targetStage,
        double ratio,
        long updatedAt) {
}
