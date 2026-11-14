package com.llmserving.worker.runtime;

import com.llmserving.common.StageResponse;
import com.llmserving.common.WeightLoadResponse;
import com.llmserving.worker.adapter.InferenceEnginePort;
import com.llmserving.worker.adapter.StreamPort;
import com.llmserving.worker.adapter.WeightStorePort;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.scheduling.StepPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GPU에 가중치를 올리고 스텝을 실행 (EA ModelRuntime, U1). 엔진 호출은 InferenceEnginePort에 위임하고,
 * 토큰 전달(StreamPublisher)과 가중치 적재 요청(Model Manager)은 ModelRuntime이 한다.
 */
@Slf4j
@Component
public class ModelRuntime {

    private final InferenceEnginePort engine;
    private final StreamPort streamPort;
    private final WeightStorePort weightStorePort;
    private final String workerId;
    private volatile double kvCapacityGb;
    private volatile StageResponse loadedStage;

    public ModelRuntime(InferenceEnginePort engine, StreamPort streamPort, WeightStorePort weightStorePort,
                        WorkerProperties properties) {
        this.engine = engine;
        this.streamPort = streamPort;
        this.weightStorePort = weightStorePort;
        this.workerId = properties.instanceId();
    }

    /** 스텝 실행 (sd1 20~21번, sd2 6~7번). 나온 토큰은 Front Gateway로 푸시한다. */
    public StepResult execute(StepPlan plan) {
        StepResult result = engine.execute(plan);
        result.chunks().forEach(streamPort::publish);
        return result;
    }

    /** 가중치 적재 (sd6 8~9번): Model Manager에서 단계 가중치를 받아 엔진에 올린다. */
    public void loadWeights(StageResponse stage) {
        kvCapacityGb = 0;
        WeightLoadResponse weights = weightStorePort.loadWeights(workerId, stage.name());
        engine.loadWeights(stage);
        kvCapacityGb = weights.kvGbPerGpu();
        loadedStage = stage;
        log.info("event=model.weights-loaded stage={} kvGbPerGpu={} msg=가중치 적재 완료", stage.name(), kvCapacityGb);
    }

    /** 생성 시험 (sd3 18번, sd6 10번). */
    public boolean generationTest() {
        return engine.generationTest();
    }

    public double kvCapacityGb() {
        return kvCapacityGb;
    }

    public StageResponse loadedStage() {
        return loadedStage;
    }
}
