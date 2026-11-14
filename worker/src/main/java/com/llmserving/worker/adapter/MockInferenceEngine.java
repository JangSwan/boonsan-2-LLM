package com.llmserving.worker.adapter;

import com.llmserving.common.StageResponse;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GPU를 대신하는 가짜 엔진 (mock 프로필). 판단하지 않고 StepPlan을 그대로 따른다.
 * 스텝 시간 = 기본 시간 + a × (Prefill 조각 토큰 수) + b × (Decode 요청 수) 만큼 지연한 뒤 결과를 돌려준다.
 * <ul>
 *   <li>긴 입력을 한 번에 처리하지 않는다: 스텝에 담긴 조각만큼만 읽는다.</li>
 *   <li>짧은 요청을 알아서 먼저 처리하지 않는다: 스텝에 담긴 요청만 처리한다.</li>
 *   <li>남은 여유를 Broker에 알리지 않는다: 크레딧 계산은 CreditManager의 일이다.</li>
 * </ul>
 * 입력 길이는 본문 길이 / charsPerToken으로 추정한다 (Front Gateway와 같은 값). 스케줄러가 조각 0을 보내면
 * 스케줄러 기준으로 입력을 다 보낸 것이므로 Prefill을 끝낸다.
 * 본문에 crashMarker가 든 요청을 실행하면 엔진이 비정상 종료한다 (sd4 장애 주입): 진행 중 요청이 모두 사라지고
 * crashRestartMs 동안 실행을 거부한다.
 */
@Slf4j
@Component
@Profile("mock")
public class MockInferenceEngine implements InferenceEnginePort {

    private final WorkerProperties.Engine config;
    private final Clock clock;
    private final Map<String, Generation> generations = new HashMap<>();
    private volatile long downUntil;

    public MockInferenceEngine(WorkerProperties properties, Clock clock) {
        this.config = properties.engine();
        this.clock = clock;
    }

    @Override
    public synchronized StepResult execute(StepPlan plan) {
        if (clock.millis() < downUntil) {
            throw new EngineFailureException("엔진 재시작 중");
        }
        plan.prompts().forEach((id, prompt) -> generations.put(id, new Generation(id, prompt)));
        boolean crash = planMembers(plan).stream()
                .map(generations::get)
                .anyMatch(g -> g != null && g.poison);
        sleep(stepTimeMs(plan));
        if (crash) {
            int lost = generations.size();
            generations.clear();
            downUntil = clock.millis() + config.crashRestartMs();
            log.error("event=engine.crashed lost={} msg=가짜 엔진 비정상 종료 (장애 주입), 진행 중 요청 소실", lost);
            throw new EngineFailureException("엔진 비정상 종료 (장애 주입)");
        }

        List<StreamChunk> chunks = new ArrayList<>();
        List<String> prefillDone = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        plan.prefillChunks().forEach((id, tokens) -> {
            Generation g = generations.computeIfAbsent(id, key -> new Generation(key, ""));
            g.remainingPrefill -= tokens;
            if (g.remainingPrefill <= 0 || tokens == 0) {
                prefillDone.add(id);
                emit(g, chunks, finished);
            }
        });
        for (String id : plan.decodeIds()) {
            Generation g = generations.get(id);
            if (g != null) {
                emit(g, chunks, finished);
            }
        }
        finished.forEach(generations::remove);
        return new StepResult(chunks, prefillDone, finished);
    }

    @Override
    public void loadWeights(StageResponse stage) {
        sleep(config.loadDelayMs());
        log.info("event=engine.weights-loaded stage={} msg=가짜 엔진 가중치 적재", stage.name());
    }

    @Override
    public boolean generationTest() {
        return clock.millis() >= downUntil;
    }

    long stepTimeMs(StepPlan plan) {
        return config.stepBaseMs()
                + Math.round(config.prefillMsPerToken() * plan.prefillTokens())
                + Math.round(config.decodeMsPerRequest() * plan.decodeIds().size());
    }

    private void emit(Generation g, List<StreamChunk> chunks, List<String> finished) {
        int seq = g.generated++;
        boolean last = g.generated >= g.targetOutput;
        chunks.add(new StreamChunk(g.requestId, seq, "토큰" + seq + " ", seq == 0, last));
        if (last) {
            finished.add(g.requestId);
        }
    }

    private static List<String> planMembers(StepPlan plan) {
        List<String> ids = new ArrayList<>(plan.decodeIds());
        ids.addAll(plan.prefillChunks().keySet());
        return ids;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Generation {
        final String requestId;
        final int targetOutput;
        final boolean poison;
        int remainingPrefill;
        int generated;

        Generation(String requestId, String prompt) {
            this.requestId = requestId;
            int chars = prompt.codePointCount(0, prompt.length());
            this.remainingPrefill = Math.max(1, (chars + config.charsPerToken() - 1) / config.charsPerToken());
            int span = Math.max(0, config.outputTokensMax() - config.outputTokensMin());
            this.targetOutput = Math.max(1, config.outputTokensMin() + Math.floorMod(requestId.hashCode(), span + 1));
            this.poison = config.crashMarker() != null && !config.crashMarker().isEmpty()
                    && prompt.contains(config.crashMarker());
        }
    }
}
