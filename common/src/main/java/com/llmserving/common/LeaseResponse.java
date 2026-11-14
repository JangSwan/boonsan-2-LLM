package com.llmserving.common;

/**
 * 발급된 lease 1건 (Broker → Worker). 작업 본문을 함께 담는다.
 *
 * @param leaseId    lease ID (UUID)
 * @param workerId   점유한 워커 ID
 * @param expiresAt  만료 시각 (epoch ms)
 * @param request    점유한 작업
 * @param retryCount 지금까지의 재시도 횟수
 * @param epoch      발급한 주 브로커의 epoch (fencing)
 */
public record LeaseResponse(
        String leaseId,
        String workerId,
        long expiresAt,
        Request request,
        int retryCount,
        long epoch) {
}
