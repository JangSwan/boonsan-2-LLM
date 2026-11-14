package com.llmserving.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.StreamChunk;
import com.llmserving.worker.WorkerTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineSchedulerTest {

    private final WorkerTestSupport.Engine engine = new WorkerTestSupport.Engine();
    private final EngineScheduler scheduler = engine.scheduler;

    private RequestState job(String id, int tokens) {
        return new RequestState(id, "v1", tokens, "가".repeat(tokens * 4), engine.clock.millis());
    }

    @Test
    void 스텝은_Decode_전부와_Prefill_조각_합계가_B_이하다() {
        scheduler.submit(job("long", 4000));

        StepPlan plan = scheduler.buildStep();

        assertThat(plan.budget()).isEqualTo(256);  // 4000×100/3000 = 134 → 하한 256
        assertThat(plan.decodeIds().size() + plan.prefillTokens()).isLessThanOrEqualTo(plan.budget());
        assertThat(plan.prefillChunks()).containsEntry("long", 256);
        assertThat(plan.prompts()).containsKey("long");
    }

    @Test
    void 긴_입력은_여러_스텝에_조각으로_나눠_읽고_본문은_첫_조각에만_넘긴다() {
        scheduler.submit(job("long", 600));

        StepPlan first = scheduler.buildStep();
        scheduler.onStepDone(engine.modelRuntime.execute(first));
        StepPlan second = scheduler.buildStep();
        scheduler.onStepDone(engine.modelRuntime.execute(second));
        StepPlan third = scheduler.buildStep();

        assertThat(first.prefillChunks()).containsEntry("long", 256);
        assertThat(second.prefillChunks()).containsEntry("long", 256);
        assertThat(second.prompts()).isEmpty();
        assertThat(third.prefillChunks()).containsEntry("long", 88);
    }

    @Test
    void 짧은_요청은_다음_스텝에_통째로_끼어들어_긴_입력보다_먼저_첫_토큰을_낸다() {
        scheduler.submit(job("L", 4000));
        scheduler.onStepDone(engine.modelRuntime.execute(scheduler.buildStep()));

        scheduler.submit(job("S", 50));
        StepPlan next = scheduler.buildStep();

        assertThat(next.prefillChunks()).containsEntry("S", 50);
        assertThat(next.prefillChunks().get("L")).isEqualTo(next.budget() - 50);
        scheduler.onStepDone(engine.modelRuntime.execute(next));

        List<StreamChunk> firsts = engine.published.stream().filter(StreamChunk::isFirst).toList();
        assertThat(firsts).extracting(StreamChunk::requestId).containsExactly("S");
        assertThat(engine.listener.events).contains("first:S", "progress:L");
    }

    @Test
    void 끝까지_실행하면_완료_이벤트와_마지막_조각을_낸다() {
        scheduler.submit(job("a", 10));   // 가짜 엔진 출력 3토큰

        for (int i = 0; i < 5; i++) {
            scheduler.runStep();
        }

        assertThat(engine.listener.events).containsSubsequence("first:a", "progress:a", "completed:a");
        assertThat(engine.published).extracting(StreamChunk::seq).containsExactly(0, 1, 2);
        assertThat(engine.published.get(2).isLast()).isTrue();
        assertThat(scheduler.activeSessions()).isZero();
    }

    @Test
    void 엔진이_비정상_종료하면_진행_중_요청을_잃고_완료를_알리지_않는다() {
        scheduler.submit(new RequestState("x", "v1", 10, "[[crash]] 엔진을 죽이는 질문", engine.clock.millis()));
        scheduler.submit(job("y", 10));

        scheduler.runStep();

        assertThat(scheduler.activeSessions()).isZero();
        assertThat(engine.listener.events).isEmpty();
        assertThat(scheduler.freeCapacity().freeSlots()).isEqualTo(8);
    }

    @Test
    void 남은_여유는_슬롯_KV_미시작_토큰으로_알려준다() {
        scheduler.submit(job("a", 1000));
        scheduler.submit(job("b", 200));

        var capacity = scheduler.freeCapacity();

        assertThat(capacity.freeSlots()).isEqualTo(6);
        assertThat(capacity.freeKvGb()).isEqualTo(5.0);   // 6.0 - 2 × 0.5
        assertThat(capacity.unstartedTokens()).isEqualTo(1200);
    }
}
