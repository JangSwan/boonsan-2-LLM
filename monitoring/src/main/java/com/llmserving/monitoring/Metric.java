package com.llmserving.monitoring;

/**
 * 지표 한 건 (EA Metric, entity).
 *
 * @param name  지표 이름 (예: queue.length, worker.worker-1.ttft-p95-ms)
 * @param value 값
 * @param at    측정 시각 (epoch 밀리초)
 */
public record Metric(String name, double value, long at) {
}
