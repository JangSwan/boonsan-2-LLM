package com.llmserving.common;

import java.util.List;

/**
 * 크레딧 pull 응답. LeaseLimiter가 허용한 k(≤ n)건의 작업과 lease.
 *
 * @param workerId 워커 ID
 * @param leases   발급된 lease (작업 포함). 허용 수가 0이면 빈 목록
 * @param epoch    응답한 주 브로커의 epoch (sd5 fencing)
 */
public record PullResponse(
        String workerId,
        List<LeaseResponse> leases,
        long epoch) {
}
