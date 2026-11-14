package com.llmserving.common;

import java.util.List;

/**
 * pull 응답. LeaseLimiter가 허용한 k(≤ n)건만 담긴다.
 *
 * @param leases 발급된 lease 목록
 * @param epoch  응답한 주 브로커의 epoch (fencing)
 */
public record PullResponse(
        List<LeaseResponse> leases,
        long epoch) {
}
