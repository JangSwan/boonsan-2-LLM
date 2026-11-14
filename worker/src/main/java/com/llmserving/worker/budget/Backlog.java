package com.llmserving.worker.budget;

/**
 * 미시작 Prefill 토큰, 진행 중 Decode 수 (EA Backlog, entity).
 *
 * @param unstartedPrefill 아직 읽지 않은 Prefill 토큰 수
 * @param decodeCount      Decode 중인 요청 수
 */
public record Backlog(int unstartedPrefill, int decodeCount) {
}
