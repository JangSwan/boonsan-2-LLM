package com.llmserving.common;

/**
 * lease 갱신 응답 (Broker → Worker LeaseRenewer).
 *
 * @param leaseId   lease ID
 * @param expiresAt 연장된 만료 시각 (epoch ms)
 * @param epoch     응답한 주 브로커의 epoch (fencing)
 */
public record RenewResponse(
        String leaseId,
        long expiresAt,
        long epoch) {
}
