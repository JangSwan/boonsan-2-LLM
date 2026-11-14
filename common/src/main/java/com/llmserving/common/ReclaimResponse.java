package com.llmserving.common;

/**
 * 워커 lease 회수 응답 (Broker → Membership LeaseReclaimer).
 *
 * @param workerId       회수 대상 워커 ID
 * @param reclaimedCount 공용 큐로 되돌린 미완료 요청 수
 */
public record ReclaimResponse(
        String workerId,
        int reclaimedCount) {
}
