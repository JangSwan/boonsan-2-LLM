package com.llmserving.common;

/**
 * v2 비율 조회 응답 (ModelConfigClient.getRatio → ModelConfig.getRatio).
 * VersionTagger는 ratio만큼 targetVersion, 나머지는 activeVersion을 태그한다.
 *
 * @param ratio         v2 비율 = v2 준비 워커 수 / 인출 중인 워커 수 (0.0 ~ 1.0)
 * @param activeVersion 활성 버전 태그 (v1)
 * @param targetVersion 목표 버전 태그 (v2). 교체 중이 아니면 null
 */
public record RatioResponse(
        double ratio,
        String activeVersion,
        String targetVersion) {
}
