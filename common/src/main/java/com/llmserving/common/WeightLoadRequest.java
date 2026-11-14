package com.llmserving.common;

/**
 * 가중치 적재 요청 (ModelRuntime.loadWeights → WeightStorePort → ModelStorePort.loadWeights).
 *
 * @param workerId  적재하는 워커 ID
 * @param stageName 적재할 단계 이름
 */
public record WeightLoadRequest(
        String workerId,
        String stageName) {
}
