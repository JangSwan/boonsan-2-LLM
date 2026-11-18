package com.llmserving.common;

import java.util.List;

/**
 * 크레딧 pull 응답. LeaseLimiter가 허용한 k(≤ n)건의 작업과 lease.
 *
 * @param workerId 워커 ID
 * @param leases   발급된 lease (작업 포함). 허용 수가 0이면 빈 목록
 * @param epoch    응답한 주 브로커의 epoch (sd5 fencing)
 * @param remainingForVersion 이번 인출 뒤 공용 큐에 남은 같은 버전 태그 작업 수. 현재 버전의 마지막 워커가
 *                            옛 버전 작업을 다 가져갔는지 판단하는 데 쓴다 (sd6). n = 0으로 보내면 인출 없이 이 값만 받는다
 */
public record PullResponse(
        String workerId,
        List<LeaseResponse> leases,
        long epoch,
        int remainingForVersion) {
}
