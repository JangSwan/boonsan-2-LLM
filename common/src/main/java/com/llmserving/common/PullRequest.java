package com.llmserving.common;

/**
 * 크레딧 pull 요청 (CreditManager → LeaseManager.pull(workerId, versionTag, n)).
 *
 * @param workerId   워커 ID
 * @param versionTag 워커가 처리할 버전 태그. 버전 일치 작업만 인출한다
 * @param n          워커가 계산한 크레딧 (당겨 갈 최대 건수)
 */
public record PullRequest(
        String workerId,
        String versionTag,
        int n) {
}
