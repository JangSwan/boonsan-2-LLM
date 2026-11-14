package com.llmserving.common;

/**
 * 크레딧 pull 요청 (Worker CreditManager → Broker LeaseManager).
 *
 * @param workerId   워커 ID
 * @param versionTag 워커가 처리할 수 있는 버전 태그
 * @param n          크레딧 수 (실제 여유로 계산한 요청 건수)
 */
public record PullRequest(
        String workerId,
        String versionTag,
        int n) {
}
