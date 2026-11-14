package com.llmserving.common;

/**
 * 워커 lease 회수 응답 (LeaseManager.reclaim). 멱등: 이미 회수했으면 reclaimedCount = 0.
 *
 * @param workerId       회수 대상 워커 ID
 * @param reclaimedCount 이번 호출에서 공용 큐로 되돌린 작업 수
 * @param epoch          응답한 주 브로커의 epoch
 */
public record ReclaimResponse(
        String workerId,
        int reclaimedCount,
        long epoch) {
}
