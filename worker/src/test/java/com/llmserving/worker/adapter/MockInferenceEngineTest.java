package com.llmserving.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.worker.WorkerTestSupport;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockInferenceEngineTest {

    private final WorkerTestSupport.MutableClock clock = new WorkerTestSupport.MutableClock();
    private final MockInferenceEngine engine = new MockInferenceEngine(WorkerTestSupport.properties(), clock);

    @Test
    void 스텝_시간은_기본_시간_더하기_a_곱하기_Prefill_더하기_b_곱하기_Decode다() {
        WorkerProperties p = WorkerTestSupport.properties();
        WorkerProperties props = new WorkerProperties(p.instanceId(), false, 8, 1, 1, 1, 1, 1, 1, p.brokerUrls(),
                p.membershipUrls(), "", "", 1, p.budget(), p.credit(),
                new WorkerProperties.Engine(20, 0.02, 1.0, 5, 1, 1, 4, "", 0, 0, "", "", 1));
        MockInferenceEngine timed = new MockInferenceEngine(props, clock);

        long ms = timed.stepTimeMs(new StepPlan(List.of("d1", "d2", "d3"), Map.of("p", 1000), 1003, Map.of()));

        assertThat(ms).isEqualTo(20 + 20 + 3);
    }

    @Test
    void StepPlan의_조각만큼만_읽고_긴_입력을_한_번에_처리하지_않는다() {
        String prompt = "가".repeat(4000);  // 1000토큰
        StepResult first = engine.execute(new StepPlan(List.of(), Map.of("L", 256), 256, Map.of("L", prompt)));
        StepResult second = engine.execute(new StepPlan(List.of(), Map.of("L", 744), 744, Map.of()));

        assertThat(first.prefillDoneIds()).isEmpty();
        assertThat(first.chunks()).isEmpty();
        assertThat(second.prefillDoneIds()).containsExactly("L");
        assertThat(second.chunks()).singleElement().matches(c -> c.isFirst() && c.seq() == 0);
    }

    @Test
    void 스텝에_없는_요청은_처리하지_않는다() {
        engine.execute(new StepPlan(List.of(), Map.of("a", 10), 256, Map.of("a", "짧은 질문")));

        StepResult idle = engine.execute(new StepPlan(List.of(), Map.of(), 256, Map.of()));

        assertThat(idle.chunks()).isEmpty();
    }

    @Test
    void 장애_주입_요청을_실행하면_비정상_종료하고_재시작_시간_동안_실행을_거부한다() {
        assertThatThrownBy(() -> engine.execute(new StepPlan(List.of(), Map.of("x", 10), 256,
                Map.of("x", "[[crash]]"))))
                .isInstanceOf(EngineFailureException.class);
        assertThatThrownBy(() -> engine.execute(new StepPlan(List.of(), Map.of(), 256, Map.of())))
                .isInstanceOf(EngineFailureException.class);
        assertThat(engine.generationTest()).isFalse();

        clock.advanceMs(2_000);
        assertThat(engine.generationTest()).isTrue();
    }
}
