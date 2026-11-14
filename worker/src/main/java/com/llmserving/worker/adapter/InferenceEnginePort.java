package com.llmserving.worker.adapter;

import com.llmserving.common.StageResponse;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;

/**
 * 추론 엔진 계약 (docs/design.md 5절). mock(MockInferenceEngine)과 real(VllmEngineAdapter)이 같은 계약을 지킨다.
 * 엔진이 응답하지 않거나 비정상 종료하면 EngineFailureException을 던진다.
 */
public interface InferenceEnginePort {

    /** StepPlan을 그대로 실행한다. 엔진은 판단하지 않는다. */
    StepResult execute(StepPlan plan);

    void loadWeights(StageResponse stage);

    boolean generationTest();
}
