package com.llmserving.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ModelConfigResponse;
import com.llmserving.common.StageResponse;
import com.llmserving.common.WorkerStatus;
import com.llmserving.worker.WorkerTestSupport;
import com.llmserving.worker.adapter.ModelConfigPort;
import com.llmserving.worker.credit.CreditManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerAgentTest {

    private final WorkerTestSupport.Engine engine = new WorkerTestSupport.Engine();
    private final WorkerTestSupport.FakeLeasePort leasePort = new WorkerTestSupport.FakeLeasePort();
    private final CreditManager credit = new CreditManager(engine.scheduler, leasePort, engine.clock,
            engine.properties);
    private final List<WorkerStatus> heartbeats = new ArrayList<>();
    private final FakeModelConfig modelConfig = new FakeModelConfig();
    private final WorkerAgent agent = new WorkerAgent(engine.properties, credit, engine.modelRuntime,
            heartbeats::add, modelConfig, engine.clock);

    @Test
    void 기동하면_활성_단계를_적재하고_시험을_통과한_뒤_인출을_시작해_상태를_보고한다() {
        agent.boot();
        agent.onConfigChanged();

        assertThat(credit.isPulling()).isTrue();
        assertThat(credit.getVersionTag()).isEqualTo("v1");
        assertThat(heartbeats).last().matches(WorkerStatus::selfTestPassed).matches(s -> "PULLING".equals(s.state()));
    }

    @Test
    void 기동_신호_전에는_하트비트도_설정_감지도_하지_않는다() {
        agent.sendHeartbeat();
        agent.onConfigChanged();

        assertThat(heartbeats).isEmpty();
        assertThat(credit.isPulling()).isFalse();
    }

    @Test
    void 목표_버전이_생기면_드레인_허가를_받아_인출_중지_드레인_재적재_준비_보고_순으로_교체한다() {
        agent.boot();
        agent.onConfigChanged();
        modelConfig.target("v2", WorkerTestSupport.FP8);

        agent.onConfigChanged();

        assertThat(modelConfig.calls).containsExactly("drain:worker-1", "ready:worker-1");
        assertThat(credit.getVersionTag()).isEqualTo("v2");
        assertThat(credit.isPulling()).isTrue();
        assertThat(engine.modelRuntime.loadedStage().name()).isEqualTo("FP8");
    }

    @Test
    void 다른_워커가_교체_중이면_기다린다() {
        agent.boot();
        agent.onConfigChanged();
        modelConfig.target("v2", WorkerTestSupport.FP8);
        modelConfig.grant = false;

        agent.onConfigChanged();

        assertThat(credit.getVersionTag()).isEqualTo("v1");
        assertThat(credit.isPulling()).isTrue();
    }

    @Test
    void 진행_중_답변이_끝날_때까지_재적재하지_않는다() {
        agent.boot();
        agent.onConfigChanged();
        leasePort.queue.add(WorkerTestSupport.request("in-flight", 40));
        credit.pullJobs();
        modelConfig.target("v2", WorkerTestSupport.FP8);

        agent.onConfigChanged();

        assertThat(credit.isPulling()).isFalse();
        assertThat(modelConfig.calls).containsExactly("drain:worker-1");
        assertThat(engine.modelRuntime.loadedStage().name()).isEqualTo("U0");

        credit.onCompleted("in-flight");
        agent.onConfigChanged();
        assertThat(credit.getVersionTag()).isEqualTo("v2");
    }

    @Test
    void 현재_버전의_마지막_워커는_남은_작업이_없을_때까지_인출하다가_멈춘다() {
        agent.boot();
        agent.onConfigChanged();
        modelConfig.target("v2", WorkerTestSupport.FP8);
        modelConfig.last = true;

        agent.onConfigChanged();
        assertThat(credit.isPulling()).isTrue();   // 옛 버전 작업을 마저 가져간다

        engine.clock.advanceMs(3_000);
        agent.onConfigChanged();
        assertThat(credit.getVersionTag()).isEqualTo("v2");
    }

    @Test
    void 마지막_워커는_슬롯이_꽉_차_인출하지_못해도_큐에_옛_버전_작업이_남아_있으면_멈추지_않는다() {
        agent.boot();
        agent.onConfigChanged();
        for (int i = 0; i < 11; i++) {
            leasePort.queue.add(WorkerTestSupport.request("old-" + i, 40));
        }
        credit.pullJobs();                          // 슬롯 8개가 다 차고 큐에 v1 작업 3건이 남는다
        assertThat(credit.inFlightCount()).isEqualTo(8);
        assertThat(credit.computeCredit()).isZero();
        modelConfig.target("v2", WorkerTestSupport.FP8);
        modelConfig.last = true;

        agent.onConfigChanged();                    // 드레인 허가 (마지막 v1 워커)
        engine.clock.advanceMs(10_000);             // 크레딧 0이라 빈 pull만 이어지는 시간
        agent.onConfigChanged();

        assertThat(credit.isPulling()).isTrue();    // 남은 v1 작업을 가져갈 워커가 없어지면 안 된다
        assertThat(leasePort.queue).hasSize(3);
    }

    @Test
    void 마지막_워커는_큐의_옛_버전_작업과_진행_중_답변이_모두_끝난_뒤_조용한_시간이_지나면_교체한다() {
        agent.boot();
        agent.onConfigChanged();
        leasePort.queue.add(WorkerTestSupport.request("old-0", 40));
        credit.pullJobs();
        modelConfig.target("v2", WorkerTestSupport.FP8);
        modelConfig.last = true;

        agent.onConfigChanged();
        engine.clock.advanceMs(3_000);
        agent.onConfigChanged();
        assertThat(credit.getVersionTag()).isEqualTo("v1");   // 진행 중 답변이 남아 있다

        credit.onCompleted("old-0");
        agent.onConfigChanged();                               // 남은 것 0을 처음 확인한 시각부터 다시 센다
        assertThat(credit.getVersionTag()).isEqualTo("v1");
        engine.clock.advanceMs(3_000);
        agent.onConfigChanged();                               // 인출 중지
        agent.onConfigChanged();                               // 재적재, 준비 보고
        assertThat(credit.getVersionTag()).isEqualTo("v2");
    }

    @Test
    void 회수_통지로_인출이_멈춘_워커는_교체하지_않는다() {
        agent.boot();
        agent.onConfigChanged();
        agent.stopPull();
        agent.stopPull();   // 멱등
        modelConfig.target("v2", WorkerTestSupport.FP8);

        agent.onConfigChanged();

        assertThat(credit.isPulling()).isFalse();
        assertThat(modelConfig.calls).isEmpty();
        assertThat(agent.getState()).isEqualTo("STOPPED");
    }

    static class FakeModelConfig implements ModelConfigPort {
        final List<String> calls = new ArrayList<>();
        String targetVersion;
        StageResponse targetStage;
        boolean grant = true;
        boolean last;

        void target(String version, StageResponse stage) {
            targetVersion = version;
            targetStage = stage;
        }

        @Override
        public ModelConfigResponse fetch(String workerId, String versionTag) {
            return new ModelConfigResponse("llama-3.1-8b", "v1", WorkerTestSupport.U0, targetVersion, targetStage, 0);
        }

        @Override
        public DrainStartResponse drainStart(String workerId) {
            if (grant) {
                calls.add("drain:" + workerId);
            }
            return new DrainStartResponse(workerId, grant, last);
        }

        @Override
        public void ready(String workerId) {
            calls.add("ready:" + workerId);
        }
    }
}
