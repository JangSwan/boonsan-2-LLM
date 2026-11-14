package com.llmserving.common;

/**
 * 버전 비율 조회 결과 (Model Manager → Front Gateway).
 *
 * @param ratio v2 비율 = v2 준비 워커 수 / 인출 중인 워커 수
 */
public record RatioResponse(double ratio) {
}
