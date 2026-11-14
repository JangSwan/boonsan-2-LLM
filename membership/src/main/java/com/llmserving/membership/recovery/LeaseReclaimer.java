package com.llmserving.membership.recovery;

import com.llmserving.membership.adapter.LeasePort;
import com.llmserving.membership.config.MembershipProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 제외된 워커의 lease 회수, 남은 4대로 재분배 (EA LeaseReclaimer, R3).
 * 회수된 미완료 요청은 broker가 공용 큐 맨 앞으로 되돌리고, 남은 워커가 평소 pull로 가져간다 (sd3 9~11번).
 */
@Slf4j
@Component
public class LeaseReclaimer {

    private final LeasePort leasePort;
    private final RecoveryNotifier recoveryNotifier;
    private final int attempts;

    public LeaseReclaimer(LeasePort leasePort, RecoveryNotifier recoveryNotifier, MembershipProperties properties) {
        this.leasePort = leasePort;
        this.recoveryNotifier = recoveryNotifier;
        this.attempts = Math.max(1, properties.reclaimAttempts());
    }

    public void reclaim(String workerId) {
        recoveryNotifier.recordFailure(workerId);
        for (int i = 1; i <= attempts; i++) {
            try {
                int count = leasePort.reclaim(workerId);
                log.info("event=lease.reclaimed workerId={} count={} msg=lease 회수, 미완료 요청 공용 큐로 반환",
                        workerId, count);
                recoveryNotifier.onRedistributed(workerId);
                return;
            } catch (RuntimeException e) {
                log.warn("event=lease.reclaim-failed workerId={} attempt={} msg=lease 회수 실패: {}",
                        workerId, i, e.getMessage());
                sleep(300L * i);
            }
        }
        log.error("event=lease.reclaim-gave-up workerId={} msg=회수 실패, 남은 lease는 만료 후 재시도 경로로 처리됨",
                workerId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
