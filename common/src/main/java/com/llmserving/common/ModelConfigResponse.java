package com.llmserving.common;

/**
 * 워커의 설정 변경 감지용 조회 응답 (WorkerAgent.onConfigChanged ← ModelConfig.currentStage 등).
 * 교체 중이 아니면 targetVersion, targetStage는 null이다.
 *
 * @param activeModel   활성 모델 이름
 * @param activeVersion 활성 버전 태그 (v1)
 * @param activeStage   활성 단계
 * @param targetVersion 목표 버전 태그 (v2)
 * @param targetStage   목표 단계
 * @param ratio         현재 v2 비율
 */
public record ModelConfigResponse(
        String activeModel,
        String activeVersion,
        StageResponse activeStage,
        String targetVersion,
        StageResponse targetStage,
        double ratio) {
}
