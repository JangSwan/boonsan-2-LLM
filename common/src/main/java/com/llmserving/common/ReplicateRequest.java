package com.llmserving.common;

/**
 * 동기 복제 요청 (주 broker Replicator → 복제본 broker BrokerReplica).
 * EA ChangeRecord를 옮겨 담는다.
 *
 * @param seq     복제 순번
 * @param kind    변경 종류 (예: ENQUEUE, LEASE_ISSUED, LEASE_RENEWED, LEASE_COMPLETED, REQUEUED, QUARANTINED)
 * @param payload 변경 내용 (JSON 문자열)
 * @param epoch   보낸 주 브로커의 epoch. 낮은 epoch는 거부한다
 */
public record ReplicateRequest(
        long seq,
        String kind,
        String payload,
        long epoch) {
}
