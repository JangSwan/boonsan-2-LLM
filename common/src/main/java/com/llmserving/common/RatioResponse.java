package com.llmserving.common;

/**
 * v2 비율 조회 응답 (ModelConfigClient.getRatio → ModelConfig.getRatio).
 *
 * @param ratio v2 비율 = v2 준비 워커 수 / 인출 중인 워커 수 (0.0 ~ 1.0)
 */
public record RatioResponse(double ratio) {
}
