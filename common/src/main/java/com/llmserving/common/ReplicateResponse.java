package com.llmserving.common;

/**
 * 동기 복제 확인 (복제본 → 주 broker).
 *
 * @param seq     확인한 복제 순번
 * @param applied 반영 여부
 * @param epoch   복제본이 아는 현재 epoch
 */
public record ReplicateResponse(
        long seq,
        boolean applied,
        long epoch) {
}
