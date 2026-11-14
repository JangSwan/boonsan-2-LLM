package com.llmserving.membership.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.AlertRequest;
import com.llmserving.membership.MembershipFixture;
import com.llmserving.membership.recovery.RecoveryNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** sd3: 회수 통지 → 인출 중지 → 하트비트 끊김 → 제외 → 회수 → 재보충 → 합류. */
class HealthDetectorTest {

    private final MembershipFixture.MutableClock clock = new MembershipFixture.MutableClock();
    private final MembershipFixture.FakeLockStore lockStore = new MembershipFixture.FakeLockStore();
    private final MembershipFixture leader = new MembershipFixture("membership-1", clock, lockStore);
    private final MembershipFixture follower = new MembershipFixture("membership-2", clock, lockStore);

    @BeforeEach
    void setUp() {
        leader.leaderLock.renew();
        follower.leaderLock.renew();
        for (int i = 1; i <= 5; i++) {
            leader.healthDetector.onHeartbeat(MembershipFixture.status("worker-" + i, true));
            follower.healthDetector.onHeartbeat(MembershipFixture.status("worker-" + i, true));
        }
    }

    private void heartbeatsExcept(String missing) {
        for (int i = 1; i <= 5; i++) {
            String id = "worker-" + i;
            if (!id.equals(missing)) {
                leader.healthDetector.onHeartbeat(MembershipFixture.status(id, true));
                follower.healthDetector.onHeartbeat(MembershipFixture.status(id, true));
            }
        }
    }

    @Test
    void 리더_락은_한_인스턴스만_가진다() {
        assertThat(leader.leaderLock.isLeader()).isTrue();
        assertThat(follower.leaderLock.isLeader()).isFalse();
    }

    @Test
    void 회수_통지를_받으면_인출을_중지시키고_회수_예정으로만_표시한다() {
        leader.noticeHandler.onReclaimNotice("worker-5");
        follower.noticeHandler.onReclaimNotice("worker-5");   // 두 제어기가 함께 보내도 된다 (멱등)

        assertThat(leader.stopPulls).containsExactly("worker-5");
        assertThat(follower.stopPulls).containsExactly("worker-5");
        assertThat(leader.registry.find("worker-5")).get().extracting(Member::getState)
                .isEqualTo(Member.RECLAIM_SCHEDULED);
        leader.healthDetector.detectFailures();
        assertThat(leader.reclaimed).isEmpty();   // 아직 살아 있으니 제외하지 않는다
    }

    @Test
    void 하트비트가_끊기면_리더만_제외하고_lease를_회수하고_재보충을_요청한다() {
        clock.advanceMs(2_000);
        heartbeatsExcept("worker-5");
        clock.advanceMs(1_500);   // worker-5 마지막 하트비트로부터 3.5초 > 3초
        heartbeatsExcept("worker-5");

        leader.healthDetector.detectFailures();
        follower.healthDetector.detectFailures();

        assertThat(leader.registry.find("worker-5").orElseThrow().isExcluded()).isTrue();
        assertThat(leader.registry.activeCount()).isEqualTo(4);
        assertThat(leader.reclaimed).containsExactly("worker-5");
        assertThat(leader.instanceRequests).containsExactly("replace-worker-5");
        assertThat(follower.reclaimed).isEmpty();
        assertThat(follower.instanceRequests).isEmpty();
        assertThat(leader.alerts).extracting(AlertRequest::kind)
                .containsExactly(RecoveryNotifier.KIND_FAILURE, RecoveryNotifier.KIND_SERVICE_RECOVERED);

        leader.healthDetector.detectFailures();   // 이미 제외: 다시 하지 않는다
        assertThat(leader.reclaimed).hasSize(1);
    }

    @Test
    void 대체_서버가_시험을_통과해_하트비트를_보내면_합류를_확인하고_T재보충을_기록한다() {
        clock.advanceMs(3_500);
        leader.leaderLock.renew();
        heartbeatsExcept("worker-5");
        leader.healthDetector.detectFailures();

        leader.replenisher.onInstanceReady("worker-spare");
        follower.replenisher.onInstanceReady("worker-spare");          // 리더가 아닌 쪽은 무시
        leader.healthDetector.onHeartbeat(MembershipFixture.status("worker-spare", false));   // 적재 중
        assertThat(leader.alerts).hasSize(2);
        clock.advanceMs(2_000);
        leader.leaderLock.renew();
        clock.advanceMs(2_000);
        leader.leaderLock.renew();
        leader.healthDetector.onHeartbeat(MembershipFixture.status("worker-spare", true));    // 시험 통과
        leader.healthDetector.onHeartbeat(MembershipFixture.status("worker-spare", true));

        assertThat(leader.alerts).extracting(AlertRequest::kind).last().isEqualTo(RecoveryNotifier.KIND_REPLENISHED);
        assertThat(leader.alerts).hasSize(3);
        assertThat(leader.recoveryNotifier.recordOf("worker-5").getTReplenishMs()).isEqualTo(4_000);
        assertThat(leader.registry.activeCount()).isEqualTo(5);
    }

    @Test
    void 리더가_사라지면_락이_만료된_뒤_다른_인스턴스가_이어받는다() {
        clock.advanceMs(5_000);   // 리더가 락을 갱신하지 못함 (정지)
        follower.leaderLock.renew();

        assertThat(follower.leaderLock.isLeader()).isTrue();
        assertThat(leader.leaderLock.isLeader()).isFalse();
    }

    @Test
    void 대체_서버_요청은_같은_워커에_대해_한_번만_보낸다() {
        leader.replenisher.requestReplacement("worker-5");
        leader.replenisher.requestReplacement("worker-5");

        assertThat(leader.instanceRequests).containsExactly("replace-worker-5");
    }
}
