package com.llmserving.common;

/**
 * 주 브로커 → 복제본 동기 복제 본문 (ReplicaPort → BrokerReplica.apply).
 * broker의 ChangeRecord(EA entity)를 앱 사이로 옮기는 DTO다.
 *
 * @param seq     변경 순번
 * @param kind    변경 종류 (broker 앱이 정한다)
 * @param payload 변경 내용 (JSON 문자열)
 * @param epoch   보낸 주 브로커의 epoch. 복제본은 현재보다 낮으면 거부한다
 */
public record ReplicationRequest(
        long seq,
        String kind,
        String payload,
        long epoch) {
}
