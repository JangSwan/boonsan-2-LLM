package com.llmserving.common;

/**
 * 예비 워커 기동 신호 응답 (fake-aws → worker-spare). 멱등: 이미 기동했으면 현재 상태를 돌려준다.
 *
 * @param workerId 기동한 워커 ID (INSTANCE_ID)
 * @param state    기동 후 워커 상태
 */
public record BootResponse(
        String workerId,
        String state) {
}
