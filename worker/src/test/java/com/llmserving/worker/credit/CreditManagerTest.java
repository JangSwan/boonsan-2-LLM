package com.llmserving.worker.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.llmserving.worker.WorkerTestSupport;
import com.llmserving.worker.scheduling.RequestState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CreditManagerTest {

    private final WorkerTestSupport.Engine engine = new WorkerTestSupport.Engine();
    private final WorkerTestSupport.FakeLeasePort leasePort = new WorkerTestSupport.FakeLeasePort();
    private final CreditManager credit = new CreditManager(engine.scheduler, leasePort, engine.clock,
            engine.properties);

    @Test
    void 비어_있으면_슬롯과_KV_중_작은_쪽만큼_크레딧을_낸다() {
        // 슬롯 8, KV 6.0 / 0.5 = 12 → 8
        assertThat(credit.computeCredit()).isEqualTo(8);
    }

    @Test
    void KV가_모자라면_KV_기준으로_줄인다() {
        WorkerTestSupport.Engine small = new WorkerTestSupport.Engine();
        small.modelRuntime.loadWeights(new com.llmserving.common.StageResponse("X", "BF16", "s3://x", 6.0));
        for (int i = 0; i < 4; i++) {
            small.scheduler.submit(new RequestState("r" + i, "v1", 10, "질문", 0));
        }
        CreditManager c = new CreditManager(small.scheduler, leasePort, small.clock, small.properties);

        // 슬롯 4, KV (6.0 - 2.0) / 0.5 = 8 → 4
        assertThat(c.computeCredit()).isEqualTo(4);
    }

    @Test
    void 미시작_토큰이_상한_이상이면_당기지_않는다() {
        engine.scheduler.submit(new RequestState("huge", "v1", 16_000, "질문", 0));
        assertThat(credit.computeCredit()).isZero();
    }

    @Test
    void 가중치를_올리기_전에는_크레딧이_없다() {
        WorkerTestSupport.Engine fresh = new WorkerTestSupport.Engine(WorkerTestSupport.properties(), false);
        CreditManager c = new CreditManager(fresh.scheduler, leasePort, fresh.clock, fresh.properties);
        assertThat(c.computeCredit()).isZero();
    }

    @Test
    void 인출_중이_아니면_pull하지_않고_setVersion으로_다시_시작한다() {
        leasePort.queue.add(WorkerTestSupport.request("a", 40));

        credit.pullJobs();
        assertThat(leasePort.requestedCredits).isEmpty();

        credit.setVersion("v1");
        credit.pullJobs();
        assertThat(leasePort.requestedCredits).containsExactly(8);
        assertThat(engine.scheduler.hasJob("a")).isTrue();
        assertThat(credit.inFlightCount()).isEqualTo(1);

        credit.stopPull();
        leasePort.queue.add(WorkerTestSupport.request("b", 40));
        credit.pullJobs();
        assertThat(leasePort.requestedCredits).hasSize(1);
    }

    @Test
    void 완료_이벤트를_받으면_complete_ack를_보낸다() {
        credit.setVersion("v1");
        leasePort.queue.add(WorkerTestSupport.request("a", 40));
        credit.pullJobs();

        credit.onCompleted("a");

        await().atMost(Duration.ofSeconds(2)).until(() -> leasePort.completed.contains("a"));
        assertThat(credit.inFlightCount()).isZero();
    }
}
