package com.llmserving.broker.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.broker.BrokerFixture;
import com.llmserving.broker.queue.QueueItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaseManagerTest {

    private final BrokerFixture broker = BrokerFixture.standalone();
    private final LeaseManager leaseManager = broker.leaseManager;

    @Test
    void pull은_버전이_같은_작업만_상한_안에서_내준다() {
        broker.sharedQueue.push(BrokerFixture.item("a", "v1"));
        broker.sharedQueue.push(BrokerFixture.item("b", "v2"));
        broker.sharedQueue.push(BrokerFixture.item("c", "v1"));

        List<Lease> leases = leaseManager.pull("worker-1", "v1", 10);

        assertThat(leases).extracting(Lease::getRequestId).containsExactly("a", "c");
        assertThat(broker.sharedQueue.length()).isEqualTo(1);
        assertThat(broker.leaseLimiter.getActiveLeases()).isEqualTo(2);
    }

    @Test
    void 동시_실행_상한을_넘겨_내주지_않는다() {
        for (int i = 0; i < 6; i++) {
            broker.sharedQueue.push(BrokerFixture.item("r" + i, "v1"));
        }

        assertThat(leaseManager.pull("worker-1", "v1", 3)).hasSize(3);
        assertThat(leaseManager.pull("worker-2", "v1", 3)).hasSize(1);   // 상한 4
        assertThat(leaseManager.pull("worker-3", "v1", 3)).isEmpty();
        assertThat(broker.sharedQueue.length()).isEqualTo(2);
    }

    @Test
    void 상한에_걸려_빈_pull이어도_남은_같은_버전_작업_수를_알려준다() {
        for (int i = 0; i < 6; i++) {
            broker.sharedQueue.push(BrokerFixture.item("r" + i, "v1"));
        }
        broker.sharedQueue.push(BrokerFixture.item("new", "v2"));
        leaseManager.pull("worker-1", "v1", 4);                  // 상한 4를 다 쓴다

        assertThat(leaseManager.pull("worker-2", "v1", 4)).isEmpty();
        assertThat(leaseManager.remainingFor("v1")).isEqualTo(2);   // 빈 pull이라도 옛 버전 작업은 남아 있다
        assertThat(leaseManager.remainingFor("v2")).isEqualTo(1);
        assertThat(leaseManager.pull("worker-2", "v1", 0)).isEmpty(); // n = 0 확인은 상한을 쓰지 않는다
        assertThat(broker.leaseLimiter.getActiveLeases()).isEqualTo(4);
    }

    @Test
    void 큐에_작업이_모자라면_쓰지_않은_허용을_돌려준다() {
        broker.sharedQueue.push(BrokerFixture.item("a", "v1"));

        assertThat(leaseManager.pull("worker-1", "v1", 4)).hasSize(1);
        assertThat(broker.leaseLimiter.getActiveLeases()).isEqualTo(1);
    }

    @Test
    void 완료하면_lease를_지우고_크레딧을_돌려준다() {
        broker.sharedQueue.push(BrokerFixture.item("a", "v1"));
        Lease lease = leaseManager.pull("worker-1", "v1", 1).get(0);

        leaseManager.complete(lease.getLeaseId());

        assertThat(broker.leaseLimiter.getActiveLeases()).isZero();
        assertThatThrownBy(() -> leaseManager.complete(lease.getLeaseId()))
                .isInstanceOf(LeaseNotFoundException.class);
    }

    @Test
    void 갱신하면_만료되지_않는다() {
        broker.sharedQueue.push(BrokerFixture.item("a", "v1"));
        Lease lease = leaseManager.pull("worker-1", "v1", 1).get(0);

        broker.advance(8_000);
        leaseManager.renew(lease.getLeaseId());
        broker.advance(8_000);
        leaseManager.expireOverdue();

        assertThat(leaseManager.activeCount()).isEqualTo(1);
    }

    @Test
    void 만료되면_재시도_상한_미만이라_재투입하고_재시도_횟수를_센다() {
        broker.sharedQueue.push(BrokerFixture.item("x", "v1"));
        leaseManager.pull("worker-1", "v1", 1);

        broker.advance(10_000);
        leaseManager.expireOverdue();

        assertThat(leaseManager.activeCount()).isZero();
        assertThat(broker.leaseLimiter.getActiveLeases()).isZero();
        assertThat(broker.sharedQueue.positionOf("x")).isEqualTo(1);
        assertThat(broker.sharedQueue.items().get(0).getRetryCount()).isEqualTo(1);
    }

    @Test
    void 두_번째_만료에서_격리하고_실패를_통지한다() {
        broker.sharedQueue.push(BrokerFixture.item("x", "v1"));
        leaseManager.pull("worker-1", "v1", 1);
        broker.advance(10_000);
        leaseManager.expireOverdue();
        leaseManager.pull("worker-2", "v1", 1);
        broker.advance(10_000);
        leaseManager.expireOverdue();

        assertThat(broker.sharedQueue.length()).isZero();
        assertThat(broker.quarantineQueue.list()).extracting(i -> i.requestId()).containsExactly("x");
        assertThat(broker.failureNotifier.failed).containsExactly("x");
        assertThat(leaseManager.pull("worker-3", "v1", 1)).isEmpty();
    }

    @Test
    void 회수는_재시도_횟수를_올리지_않고_맨_앞으로_되돌리며_멱등이다() {
        broker.sharedQueue.push(BrokerFixture.item("a", "v1"));
        broker.sharedQueue.push(BrokerFixture.item("b", "v1"));
        leaseManager.pull("worker-5", "v1", 1);   // a

        assertThat(leaseManager.reclaim("worker-5")).isEqualTo(1);
        assertThat(leaseManager.reclaim("worker-5")).isZero();

        assertThat(broker.sharedQueue.positionOf("a")).isEqualTo(1);
        assertThat(broker.sharedQueue.items().get(0).getRetryCount()).isZero();
        assertThat(broker.leaseLimiter.getActiveLeases()).isZero();
    }

    @Test
    void 재시도_중인_작업은_한_번에_1건만_내줘_엔진을_죽인_요청과_같이_다니지_않게_한다() {
        for (String id : new String[] {"x", "a", "b"}) {
            broker.sharedQueue.push(BrokerFixture.item(id, "v1"));
        }
        leaseManager.pull("worker-1", "v1", 3);
        broker.advance(10_000);
        leaseManager.expireOverdue();     // x, a, b 모두 만료 1회 (엔진 장애)
        broker.sharedQueue.push(BrokerFixture.item("fresh", "v1"));

        List<Lease> next = leaseManager.pull("worker-2", "v1", 4);

        assertThat(next).extracting(l -> l.getItem().getRetryCount()).filteredOn(c -> c > 0).hasSize(1);
        assertThat(next).extracting(Lease::getRequestId).contains("fresh");
        assertThat(leaseManager.pull("worker-3", "v1", 4)).hasSize(1);
        assertThat(leaseManager.pull("worker-4", "v1", 4)).hasSize(1);
    }

    @Test
    void 고립으로_만료_처리를_커밋하지_못하면_다음_주기에_같은_만료를_두_번_세지_않는다() {
        BrokerFixture.FakeLock lock = new BrokerFixture.FakeLock();
        BrokerFixture isolated = new BrokerFixture("broker-primary", true, false, new BrokerFixture.MutableClock(), lock);
        isolated.replicator.maintain();
        isolated.sharedQueue.push(BrokerFixture.item("x", "v1"));
        isolated.leaseManager.pull("worker-1", "v1", 1);
        isolated.advance(10_000);

        lock.reachable = false;                 // 복제본도 없고 락도 갱신 못 함: 쓰기 중지 (sd5 규칙 3)
        isolated.advance(4_000);
        isolated.leaseManager.expireOverdue();
        isolated.leaseManager.expireOverdue();
        assertThat(isolated.leaseManager.activeCount()).isEqualTo(1);   // 커밋 실패: lease 그대로

        lock.reachable = true;
        isolated.replicator.maintain();
        isolated.leaseManager.expireOverdue();

        assertThat(isolated.quarantineQueue.list()).isEmpty();
        assertThat(isolated.sharedQueue.items()).singleElement()
                .extracting(QueueItem::getRetryCount).isEqualTo(1);
    }

    @Test
    void 같은_요청을_두_번_접수해도_한_번만_적재한다() {
        assertThat(broker.sharedQueue.push(BrokerFixture.item("a", "v1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.push(BrokerFixture.item("b", "v1"))).isEqualTo(2);
        assertThat(broker.sharedQueue.push(BrokerFixture.item("a", "v1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.length()).isEqualTo(2);
    }
}
