package com.llmserving.broker.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.broker.BrokerFixture;
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
    void 같은_요청을_두_번_접수해도_한_번만_적재한다() {
        assertThat(broker.sharedQueue.push(BrokerFixture.item("a", "v1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.push(BrokerFixture.item("b", "v1"))).isEqualTo(2);
        assertThat(broker.sharedQueue.push(BrokerFixture.item("a", "v1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.length()).isEqualTo(2);
    }
}
