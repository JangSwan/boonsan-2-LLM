package com.llmserving.modelmanager;

/**
 * 게이트 판정 결과 (EA GateResult, entity).
 *
 * @param passed            통과 여부
 * @param qualityScore      품질 점수
 * @param sessionsPerDollar 처리량 지표
 */
public record GateResult(boolean passed, double qualityScore, double sessionsPerDollar) {
}
