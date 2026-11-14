package com.llmserving.common;

/**
 * 인출 중지 응답 (WorkerController → WorkerAgent.stopPull). 멱등.
 *
 * @param workerId 워커 ID
 * @param pulling  호출 후 인출 여부 (중지되었으면 false)
 */
public record StopPullResponse(
        String workerId,
        boolean pulling) {
}
