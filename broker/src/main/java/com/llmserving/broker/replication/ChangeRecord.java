package com.llmserving.broker.replication;

/**
 * 복제 단위 변경 기록 (EA ChangeRecord, entity).
 *
 * @param seq     변경 순번
 * @param kind    변경 종류 (아래 상수)
 * @param payload 변경 내용 JSON (ChangePayloads의 record)
 * @param epoch   주 브로커 세대 번호. 승격 시 +1, 낮은 epoch 메시지는 거부 (fencing, sd5)
 */
public record ChangeRecord(long seq, String kind, String payload, long epoch) {

    public static final String QUEUE_PUSHED = "QUEUE_PUSHED";
    public static final String QUEUE_REQUEUED = "QUEUE_REQUEUED";
    public static final String QUEUE_REMOVED = "QUEUE_REMOVED";
    public static final String LEASES_ISSUED = "LEASES_ISSUED";
    public static final String LEASE_RENEWED = "LEASE_RENEWED";
    public static final String LEASE_REMOVED = "LEASE_REMOVED";
    public static final String QUARANTINED = "QUARANTINED";
    /** 복제본 재연결 시 전체 상태. */
    public static final String SNAPSHOT = "SNAPSHOT";
    /** 변경이 없을 때도 주 브로커가 살아 있음을 알린다 (복제본의 주 브로커 응답 감시용). */
    public static final String PING = "PING";
}
