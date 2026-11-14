package com.llmserving.broker.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.broker.BrokerFixture;
import com.llmserving.broker.lease.Lease;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 주 브로커와 복제본을 메모리에서 이어 sd5 규칙 1~5를 확인한다.
 */
class ReplicationTest {

    private BrokerFixture.MutableClock clock;
    private BrokerFixture.FakeLock lock;
    private BrokerFixture primary;
    private BrokerFixture replica;

    @BeforeEach
    void setUp() {
        clock = new BrokerFixture.MutableClock();
        lock = new BrokerFixture.FakeLock();
        replica = new BrokerFixture("broker-replica", false, false, clock, lock);
        primary = new BrokerFixture("broker-primary", true, true, clock, lock);
        primary.replicaPort.target = replica.brokerReplica;
    }

    @Test
    void 규칙1_복제본_확인_후_커밋하고_순번을_응답한다_Redis_없이도() {
        lock.reachable = false;

        int position = primary.sharedQueue.push(BrokerFixture.item("a", "v1"));

        assertThat(position).isEqualTo(1);
        assertThat(replica.sharedQueue.items()).extracting(i -> i.requestId()).containsExactly("a");
    }

    @Test
    void 인출과_lease_변경이_복제본에_그대로_반영된다() {
        primary.sharedQueue.push(BrokerFixture.item("a", "v1"));
        primary.sharedQueue.push(BrokerFixture.item("b", "v1"));
        Lease lease = primary.leaseManager.pull("worker-1", "v1", 1).get(0);
        primary.leaseManager.renew(lease.getLeaseId());

        assertThat(replica.sharedQueue.items()).extracting(i -> i.requestId()).containsExactly("b");
        assertThat(replica.leaseManager.leases()).extracting(Lease::getLeaseId).containsExactly(lease.getLeaseId());

        primary.leaseManager.complete(lease.getLeaseId());
        assertThat(replica.leaseManager.leases()).isEmpty();
    }

    @Test
    void 규칙2_복제본이_끊기면_락을_갱신하는_동안만_혼자_커밋한다() {
        primary.replicator.maintain();          // 락 획득
        primary.replicaPort.down = true;

        assertThat(primary.sharedQueue.push(BrokerFixture.item("a", "v1"))).isEqualTo(1);
    }

    @Test
    void 규칙3_복제본도_락도_없으면_쓰기를_멈춘다() {
        primary.replicaPort.down = true;
        lock.reachable = false;
        primary.replicator.maintain();

        assertThatThrownBy(() -> primary.sharedQueue.push(BrokerFixture.item("a", "v1")))
                .isInstanceOf(BrokerIsolatedException.class);
        assertThat(primary.sharedQueue.length()).isZero();
    }

    @Test
    void 승격_전_복제본은_접수와_인출에_503으로_응답한다() {
        assertThatThrownBy(() -> replica.sharedQueue.push(BrokerFixture.item("a", "v1")))
                .isInstanceOf(NotPrimaryException.class);
        assertThatThrownBy(() -> replica.leaseManager.pull("worker-1", "v1", 1))
                .isInstanceOf(NotPrimaryException.class);
    }

    @Test
    void 규칙4_주_브로커의_락이_유효하면_X초가_지나도_승격하지_않는다() {
        primary.replicator.maintain();
        primary.replicaPort.down = true;   // 네트워크 분리: 복제본에 연락이 가지 않음

        for (int i = 0; i < 10; i++) {
            clock.advanceMs(1_000);
            primary.replicator.maintain(); // 주 브로커는 락을 계속 갱신
            replica.brokerReplica.watchPrimary();
        }

        assertThat(replica.brokerReplica.role()).isEqualTo(BrokerRole.REPLICA);
        assertThat(lock.holder()).isEqualTo("broker-primary");
    }

    @Test
    void 규칙4_5_주_브로커가_사라지면_락_만료_후_승격하고_큐와_lease를_이어받으며_옛_epoch를_거부한다() {
        primary.replicator.maintain();
        primary.sharedQueue.push(BrokerFixture.item("a", "v1"));
        primary.sharedQueue.push(BrokerFixture.item("b", "v1"));
        Lease lease = primary.leaseManager.pull("worker-1", "v1", 1).get(0);

        // 주 브로커 정지: 더 이상 락 갱신도 복제도 하지 않는다.
        clock.advanceMs(2_000);
        replica.brokerReplica.watchPrimary();
        assertThat(replica.brokerReplica.role()).isEqualTo(BrokerRole.REPLICA);  // X(5초) 전

        clock.advanceMs(4_000);
        replica.brokerReplica.watchPrimary();

        assertThat(replica.brokerReplica.role()).isEqualTo(BrokerRole.PRIMARY);
        assertThat(replica.brokerReplica.epoch()).isEqualTo(2);
        assertThat(replica.sharedQueue.items()).extracting(i -> i.requestId()).containsExactly("b");
        assertThat(replica.leaseManager.leases()).extracting(Lease::getLeaseId).containsExactly(lease.getLeaseId());

        // 새 주 브로커에서 기존 lease로 계속 처리 (sd5 10~11번)
        replica.replicator.maintain();
        replica.leaseManager.renew(lease.getLeaseId());
        List<Lease> next = replica.leaseManager.pull("worker-2", "v1", 4);
        assertThat(next).extracting(Lease::getRequestId).containsExactly("b");
        assertThat(replica.leaseLimiter.getActiveLeases()).isEqualTo(2);

        // 옛 주 브로커가 돌아와 복제를 보내면 낮은 epoch로 거부되고 물러난다 (fencing)
        assertThatThrownBy(() -> replica.brokerReplica.apply(new ChangeRecord(99, ChangeRecord.PING, "{}", 1)))
                .isInstanceOf(StaleEpochException.class);
        assertThatThrownBy(() -> primary.sharedQueue.push(BrokerFixture.item("c", "v1")))
                .isInstanceOf(NotPrimaryException.class);
        assertThat(primary.brokerReplica.role()).isEqualTo(BrokerRole.FENCED);
    }

    @Test
    void 끊겼던_복제본이_돌아오면_전체_상태로_맞춘_뒤_동기_복제를_재개한다() {
        primary.replicator.maintain();
        primary.replicaPort.down = true;
        primary.sharedQueue.push(BrokerFixture.item("a", "v1"));   // 단독 커밋
        primary.leaseManager.pull("worker-1", "v1", 1);
        primary.sharedQueue.push(BrokerFixture.item("b", "v1"));

        primary.replicaPort.down = false;
        primary.replicator.maintain();                            // 재동기화

        assertThat(replica.sharedQueue.items()).extracting(i -> i.requestId()).containsExactly("b");
        assertThat(replica.leaseManager.leases()).hasSize(1);
        primary.sharedQueue.push(BrokerFixture.item("c", "v1"));
        assertThat(replica.sharedQueue.items()).extracting(i -> i.requestId()).containsExactly("b", "c");
    }
}
