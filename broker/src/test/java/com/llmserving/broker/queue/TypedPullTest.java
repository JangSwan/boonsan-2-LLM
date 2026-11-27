package com.llmserving.broker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.broker.BrokerFixture;
import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.lease.Lease;
import com.llmserving.broker.replication.BrokerRole;
import com.llmserving.common.Request;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 유형별 대기열 인출 (llm.broker.typed-pull, 실측 후 보완, 비평 QAS1 제안 채택).
 * 긴 요청 기준 2000토큰, 기한 180초 − 여유 60초 = 120초 기다리면 먼저, 짧은 요청 대기 중 워커당 긴 요청 2건.
 */
class TypedPullTest {

    private static final BrokerProperties.TypedPull ON = new BrokerProperties.TypedPull(true, 2000, 180, 60, 2);

    private BrokerFixture broker;

    @BeforeEach
    void setUp() {
        broker = typed(ON);
    }

    private static BrokerFixture typed(BrokerProperties.TypedPull typedPull) {
        BrokerFixture fixture = new BrokerFixture("broker-primary", true, false, new BrokerFixture.MutableClock(),
                new BrokerFixture.FakeLock(), 100, typedPull);
        fixture.replicator.maintain();
        return fixture;
    }

    private QueueItem shortItem(String id) {
        return new QueueItem(new Request(id, "v1", 100, 0, "짧은 질문 " + id), broker.clock.millis(), 0);
    }

    private QueueItem longItem(String id) {
        return new QueueItem(new Request(id, "v1", 4000, 0, "긴 입력 " + id), broker.clock.millis(), 0);
    }

    private List<String> pull(String workerId, int n) {
        return broker.leaseManager.pull(workerId, "v1", n).stream().map(Lease::getRequestId).toList();
    }

    @Test
    void 짧은_요청을_먼저_꺼내고_줄_안에서는_접수_순서를_지킨다() {
        broker.sharedQueue.push(longItem("L1"));
        broker.sharedQueue.push(shortItem("S1"));
        broker.sharedQueue.push(longItem("L2"));
        broker.sharedQueue.push(shortItem("S2"));

        assertThat(pull("worker-1", 3)).containsExactly("S1", "S2", "L1");
        assertThat(pull("worker-1", 3)).containsExactly("L2");
    }

    @Test
    void 기한에_가까운_긴_요청은_짧은_요청보다_먼저_꺼낸다() {
        broker.sharedQueue.push(longItem("L-old"));
        broker.clock.advanceMs(120_000);  // 180 − 60 = 120초 대기
        broker.sharedQueue.push(shortItem("S1"));
        broker.sharedQueue.push(longItem("L-new"));

        assertThat(pull("worker-1", 1)).containsExactly("L-old");
        assertThat(pull("worker-1", 2)).containsExactly("S1", "L-new");
    }

    @Test
    void 기한_전이면_짧은_요청이_앞선다() {
        broker.sharedQueue.push(longItem("L1"));
        broker.clock.advanceMs(119_999);
        broker.sharedQueue.push(shortItem("S1"));

        assertThat(pull("worker-1", 1)).containsExactly("S1");
    }

    @Test
    void 짧은_요청이_기다리는_동안만_워커당_긴_요청_수를_제한한다() {
        for (int i = 1; i <= 4; i++) {
            broker.sharedQueue.push(longItem("L" + i));
        }
        for (int i = 1; i <= 3; i++) {
            broker.sharedQueue.push(shortItem("S" + i));
        }

        // 크레딧 2: 짧은 요청 2건을 꺼내도 S3가 남아 있으므로 긴 요청은 아직 받지 않는다(크레딧이 모자라서).
        assertThat(pull("worker-1", 2)).containsExactly("S1", "S2");
        // 크레딧 5: S3를 꺼내면 짧은 요청이 남지 않으므로 긴 요청 상한이 걸리지 않는다.
        assertThat(pull("worker-1", 5)).containsExactly("S3", "L1", "L2", "L3", "L4");
    }

    @Test
    void 짧은_요청이_남아_있으면_긴_요청은_워커가_가진_것과_합쳐_상한까지만() {
        broker.sharedQueue.push(longItem("L1"));
        broker.sharedQueue.push(longItem("L2"));
        assertThat(pull("worker-1", 2)).containsExactly("L1", "L2");  // 짧은 요청이 없으니 상한 없음

        broker.sharedQueue.push(longItem("L3"));
        for (int i = 1; i <= 5; i++) {
            broker.sharedQueue.push(shortItem("S" + i));
        }
        // worker-1은 긴 요청 2건(상한)을 가진 채로, 크레딧 3으로는 짧은 요청만 받는다. 짧은 요청 2건이 남는다.
        assertThat(pull("worker-1", 3)).containsExactly("S1", "S2", "S3");
        // 다른 워커는 긴 요청이 없으므로 자리가 있지만, 짧은 요청이 먼저다.
        assertThat(pull("worker-2", 3)).containsExactly("S4", "S5", "L3");
        assertThat(broker.leaseManager.countLongLeases("worker-1")).isEqualTo(2);
        assertThat(broker.leaseManager.countLongLeases("worker-2")).isEqualTo(1);
    }

    @Test
    void 짧은_요청이_남아_있고_긴_요청_상한에_닿으면_긴_요청을_더_주지_않는다() {
        broker.sharedQueue.push(longItem("L1"));
        broker.sharedQueue.push(longItem("L2"));
        pull("worker-1", 2);
        broker.sharedQueue.push(longItem("L3"));
        broker.sharedQueue.push(shortItem("S1"));
        broker.sharedQueue.push(shortItem("S2"));

        // 크레딧 1: S1만 나가고 S2가 남는다. worker-1은 긴 요청 2건으로 상한이라 L3는 남는다.
        assertThat(pull("worker-1", 1)).containsExactly("S1");
        assertThat(broker.sharedQueue.items()).extracting(QueueItem::requestId).containsExactly("L3", "S2");
    }

    @Test
    void 순번은_자기_줄_안의_순번이다() {
        assertThat(broker.sharedQueue.push(longItem("L1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.push(shortItem("S1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.push(longItem("L2"))).isEqualTo(2);
        assertThat(broker.sharedQueue.push(shortItem("S2"))).isEqualTo(2);

        assertThat(broker.sharedQueue.positionOf("L2")).isEqualTo(2);
        assertThat(broker.sharedQueue.positionOf("S2")).isEqualTo(2);
        assertThat(broker.sharedQueue.shortLength()).isEqualTo(2);
        assertThat(broker.sharedQueue.longLength()).isEqualTo(2);

        pull("worker-1", 1);  // S1
        assertThat(broker.sharedQueue.positionOf("S2")).isEqualTo(1);
        assertThat(broker.sharedQueue.positionOf("L2")).isEqualTo(2);
        assertThat(broker.sharedQueue.positionOf("S1")).isZero();
    }

    @Test
    void 재시도_작업은_자기_줄_맨_앞으로_가고_한_번에_1건만_나간다() {
        broker.sharedQueue.push(shortItem("S1"));
        broker.sharedQueue.push(shortItem("S2"));
        broker.sharedQueue.push(longItem("L1"));
        List<Lease> leases = broker.leaseManager.pull("worker-1", "v1", 3);
        assertThat(leases).extracting(Lease::getRequestId).containsExactly("S1", "S2", "L1");

        broker.sharedQueue.push(shortItem("S3"));
        broker.advance(11_000);  // lease 만료 → 재시도 1회, 맨 앞으로
        broker.leaseManager.expireOverdue();

        // 재시도 작업은 한 번의 인출에 1건만: 짧은 요청 줄 맨 앞의 재시도 작업 1건, 그다음 새 짧은 요청.
        List<String> next = pull("worker-2", 5);
        assertThat(next.get(0)).isIn("S1", "S2");
        assertThat(next).contains("S3");
        assertThat(next.stream().filter(id -> !id.equals("S3")).count()).isEqualTo(1);
    }

    @Test
    void 꺼져_있으면_도착_순서_그대로다() {
        broker = typed(BrokerProperties.TypedPull.disabled());
        assertThat(broker.sharedQueue.push(longItem("L1"))).isEqualTo(1);
        assertThat(broker.sharedQueue.push(shortItem("S1"))).isEqualTo(2);
        broker.sharedQueue.push(longItem("L2"));

        assertThat(pull("worker-1", 3)).containsExactly("L1", "S1", "L2");
        assertThat(broker.sharedQueue.longLength()).isZero();
        assertThat(broker.leaseManager.countLongLeases("worker-1")).isZero();
    }

    @Test
    void 승격_후에도_두_줄과_워커의_긴_요청_수가_그대로다() {
        BrokerFixture.MutableClock clock = new BrokerFixture.MutableClock();
        BrokerFixture.FakeLock lock = new BrokerFixture.FakeLock();
        BrokerFixture replica = new BrokerFixture("broker-replica", false, false, clock, lock, 100, ON);
        BrokerFixture primary = new BrokerFixture("broker-primary", true, true, clock, lock, 100, ON);
        primary.replicaPort.target = replica.brokerReplica;
        primary.replicator.maintain();

        long now = clock.millis();
        primary.sharedQueue.push(new QueueItem(new Request("L1", "v1", 4000, 0, "긴"), now, 0));
        primary.sharedQueue.push(new QueueItem(new Request("L2", "v1", 4000, 0, "긴"), now, 0));
        primary.leaseManager.pull("worker-1", "v1", 1);  // L1
        primary.sharedQueue.push(new QueueItem(new Request("S1", "v1", 100, 0, "짧은"), now, 0));

        clock.advanceMs(6_000);  // 주 브로커 정지 → 락 만료 → 승격
        replica.brokerReplica.watchPrimary();
        assertThat(replica.brokerReplica.role()).isEqualTo(BrokerRole.PRIMARY);

        assertThat(replica.sharedQueue.longLength()).isEqualTo(1);
        assertThat(replica.sharedQueue.shortLength()).isEqualTo(1);
        assertThat(replica.sharedQueue.positionOf("L2")).isEqualTo(1);
        assertThat(replica.leaseManager.countLongLeases("worker-1")).isEqualTo(1);
        assertThat(replica.leaseManager.pull("worker-2", "v1", 2)).extracting(Lease::getRequestId)
                .containsExactly("S1", "L2");
    }
}
