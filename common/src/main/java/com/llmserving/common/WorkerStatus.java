package com.llmserving.common;

/**
 * 하트비트에 실리는 워커 상태 (EA Common.WorkerStatus, entity).
 * 하트비트 본문으로 membership에 보낸다.
 *
 * @param workerId        워커 ID (INSTANCE_ID)
 * @param versionTag      현재 인출 중인 버전 태그
 * @param state           워커 상태 (값은 worker 앱이 정한다. 예: PULLING, STOPPED)
 * @param unstartedTokens 미시작 Prefill 토큰 수
 * @param selfTestPassed  모델 적재 후 생성 시험 통과 여부 (sd3 합류 판단)
 */
public record WorkerStatus(
        String workerId,
        String versionTag,
        String state,
        int unstartedTokens,
        boolean selfTestPassed) {
}
