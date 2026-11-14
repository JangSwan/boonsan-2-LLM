package com.llmserving.membership.detection;

import com.llmserving.common.WorkerStatus;
import com.llmserving.membership.config.MembershipProperties;
import com.llmserving.membership.leader.LeaderLock;
import com.llmserving.membership.recovery.LeaseReclaimer;
import com.llmserving.membership.recovery.Replenisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하트비트로 장애 감지, 제외, 재보충 지시, 합류 확인 (EA HealthDetector, R3).
 * 하트비트는 두 제어기가 모두 받아 목록을 유지하고, 제외·회수·재보충·합류 확인은 리더 락을 가진 1대만 한다.
 */
@Slf4j
@Component
public class HealthDetector {

    private final MemberRegistry memberRegistry;
    private final LeaderLock leaderLock;
    private final LeaseReclaimer leaseReclaimer;
    private final Replenisher replenisher;
    private final long heartbeatTimeoutMs;

    public HealthDetector(MemberRegistry memberRegistry, LeaderLock leaderLock, LeaseReclaimer leaseReclaimer,
                          Replenisher replenisher, MembershipProperties properties) {
        this.memberRegistry = memberRegistry;
        this.leaderLock = leaderLock;
        this.leaseReclaimer = leaseReclaimer;
        this.replenisher = replenisher;
        this.heartbeatTimeoutMs = properties.heartbeatTimeoutSec() * 1000L;
    }

    /** 하트비트 · 상태 보고 (sd3 19번). 생성 시험을 새로 통과한 워커면 합류를 확인한다. */
    public void onHeartbeat(WorkerStatus status) {
        boolean wasJoined = memberRegistry.find(status.workerId()).map(Member::isJoined).orElse(false);
        memberRegistry.upsert(status);
        if (status.selfTestPassed() && !wasJoined && leaderLock.isLeader()) {
            confirmJoin(status.workerId());
        }
    }

    /** 회수 예정 알림 (sd3 4번). 아직 제외하지 않는다: 서버가 사라져 하트비트가 끊기면 그때 제외한다. */
    public void onReclaimScheduled(String workerId) {
        memberRegistry.scheduleReclaim(workerId);
        log.info("event=worker.reclaim-scheduled workerId={} msg=회수 예정 표시", workerId);
    }

    /** 주기 작업: 하트비트 끊김 감지 → 제외 → lease 회수 → 재보충 지시 (sd3 5~7번). 리더만 한다. */
    @Scheduled(fixedDelayString = "${llm.membership.detect-interval-ms}")
    public void detectFailures() {
        if (!leaderLock.isLeader()) {
            return;
        }
        for (Member member : memberRegistry.overdue(heartbeatTimeoutMs)) {
            String workerId = member.getWorkerId();
            memberRegistry.exclude(workerId);
            log.warn("event=worker.excluded workerId={} lastHeartbeat={} activeCount={} msg=하트비트 끊김, 제외 (T서비스 시작)",
                    workerId, member.getLastHeartbeat(), memberRegistry.activeCount());
            leaseReclaimer.reclaim(workerId);
            replenisher.requestReplacement(workerId);
        }
    }

    /** 합류 확인 (sd3 20번). */
    public void confirmJoin(String workerId) {
        log.info("event=worker.joined workerId={} activeCount={} msg=생성 시험 통과, 합류 확인",
                workerId, memberRegistry.activeCount());
        replenisher.onJoinConfirmed(workerId);
    }
}
