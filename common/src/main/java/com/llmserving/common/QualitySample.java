package com.llmserving.common;

/**
 * 운영 중 응답 품질 샘플 (EA Common.QualitySample, entity).
 * Monitoring SampleForwarder → Model Manager QualityGate.observe 본문.
 *
 * @param requestId  요청 ID
 * @param versionTag 응답한 모델의 버전 태그
 * @param score      품질 점수
 */
public record QualitySample(
        String requestId,
        String versionTag,
        double score) {
}
