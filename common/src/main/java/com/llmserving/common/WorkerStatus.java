package com.llmserving.common;

/**
 * 하트비트에 실리는 워커 상태 (EA Common::WorkerStatus, entity).
 *
 * @param workerId        워커 ID (INSTANCE_ID 값)
 * @param versionTag      현재 적재한 버전 태그
 * @param state           워커 상태 (예: ACTIVE, DRAINING, LOADING)
 * @param unstartedTokens 아직 시작하지 않은 Prefill 토큰 수
 * @param selfTestPassed  부팅 시 생성 시험 통과 여부
 */
public record WorkerStatus(
        String workerId,
        String versionTag,
        String state,
        int unstartedTokens,
        boolean selfTestPassed) {
}
