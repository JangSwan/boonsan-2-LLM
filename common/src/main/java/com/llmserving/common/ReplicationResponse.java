package com.llmserving.common;

/**
 * 동기 복제 확인 응답. applied = true를 받아야 주 브로커가 커밋한다 (sd5 5~6번).
 *
 * @param seq     확인한 변경 순번
 * @param epoch   복제본이 알고 있는 현재 epoch
 * @param applied 적용 여부
 */
public record ReplicationResponse(
        long seq,
        long epoch,
        boolean applied) {
}
