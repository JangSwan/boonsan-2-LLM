package com.llmserving.common;

/**
 * 큐 길이 수집 결과 (Broker → Monitoring).
 *
 * @param length 공용 큐 길이
 * @param at     수집 시각 (epoch ms)
 */
public record QueueMetricsResponse(
        int length,
        long at) {
}
