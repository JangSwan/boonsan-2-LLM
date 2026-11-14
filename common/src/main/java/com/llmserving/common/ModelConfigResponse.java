package com.llmserving.common;

/**
 * 모델 설정 조회 결과 (Model Manager → Worker WorkerAgent 설정 감지).
 *
 * @param activeModel 활성 모델
 * @param activeStage 활성 단계
 * @param targetStage 목표 단계 (교체 중이 아니면 activeStage와 같다)
 */
public record ModelConfigResponse(
        String activeModel,
        String activeStage,
        String targetStage) {
}
