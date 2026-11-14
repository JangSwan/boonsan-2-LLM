package com.llmserving.modelmanager;

import com.llmserving.common.ErrorCode;
import com.llmserving.common.QualitySample;
import com.llmserving.modelmanager.adapter.ModelStorePort;
import com.llmserving.modelmanager.config.ApiException;
import com.llmserving.modelmanager.config.ModelManagerProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 후보 단계 품질, 처리량 판정과 운영 중 품질 감시 (EA QualityGate, U1). 단계별 재검증은 여기 한 곳에 모은다.
 * <ul>
 *   <li>evaluate: 단계 점수가 최소 점수 이상이면 통과 (sd6 3번).</li>
 *   <li>observe: 버전별 최근 샘플 평균이 기준 미만이면 RollbackManager로 U0 롤백 (sd6 26~27번).</li>
 * </ul>
 */
@Slf4j
@Component
public class QualityGate {

    private final ModelStorePort modelStore;
    private final RollbackManager rollbackManager;
    private final ModelManagerProperties.Gate gate;
    private final ModelManagerProperties.Rollback rollback;
    private final Map<String, Deque<Double>> windows = new HashMap<>();

    public QualityGate(ModelStorePort modelStore, RollbackManager rollbackManager, ModelManagerProperties properties) {
        this.modelStore = modelStore;
        this.rollbackManager = rollbackManager;
        this.gate = properties.gate();
        this.rollback = properties.rollback();
    }

    public GateResult evaluate(LadderStage stage) {
        LadderStage candidate = findStage(stage.getName());
        double score = gate.qualityScores().getOrDefault(candidate.getName(), 0.0);
        double sessionsPerDollar = gate.sessionsPerDollar().getOrDefault(candidate.getName(), 0.0);
        GateResult result = new GateResult(score >= gate.minScore(), score, sessionsPerDollar);
        log.info("event=model.gate-evaluated stage={} passed={} qualityScore={} sessionsPerDollar={} msg=품질 게이트 판정",
                candidate.getName(), result.passed(), score, sessionsPerDollar);
        return result;
    }

    public void observe(QualitySample sample) {
        double average;
        synchronized (windows) {
            Deque<Double> window = windows.computeIfAbsent(sample.versionTag(), v -> new ArrayDeque<>());
            window.addLast(sample.score());
            while (window.size() > rollback.window()) {
                window.removeFirst();
            }
            if (window.size() < rollback.window()) {
                return;
            }
            average = window.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            if (average >= rollback.threshold()) {
                return;
            }
            windows.clear();
        }
        log.warn("event=model.quality-degraded versionTag={} average={} threshold={} msg=운영 중 품질 이상 감지",
                sample.versionTag(), average, rollback.threshold());
        rollbackManager.rollbackToU0();
    }

    /** 이름으로 사다리 단계를 찾는다 (가중치 저장소 기준). */
    public LadderStage findStage(String name) {
        return modelStore.listStages().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.STAGE_NOT_FOUND, "없는 사다리 단계: " + name));
    }
}
