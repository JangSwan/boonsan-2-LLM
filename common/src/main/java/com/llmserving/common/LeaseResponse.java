package com.llmserving.common;

/**
 * lease 1건 응답. pull 결과의 원소이자 renew 응답이다.
 *
 * @param leaseId   lease ID (UUID)
 * @param requestId 요청 ID
 * @param workerId  lease를 가진 워커 ID
 * @param expiresAt 만료 시각 (epoch 밀리초)
 * @param request   점유한 작업 본문 (renew 응답에서는 null)
 * @param epoch     발급·갱신한 주 브로커의 epoch (sd5 fencing)
 */
public record LeaseResponse(
        String leaseId,
        String requestId,
        String workerId,
        long expiresAt,
        Request request,
        long epoch) {
}
