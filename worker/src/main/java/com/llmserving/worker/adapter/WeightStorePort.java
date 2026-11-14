package com.llmserving.worker.adapter;

import com.llmserving.common.WeightLoadResponse;

/**
 * 가중치 적재 포트 (ModelRuntime.loadWeights → model-manager ModelConfigController → ModelStorePort).
 */
public interface WeightStorePort {

    WeightLoadResponse loadWeights(String workerId, String stageName);
}
