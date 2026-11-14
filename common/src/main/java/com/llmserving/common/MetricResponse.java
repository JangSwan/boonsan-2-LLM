package com.llmserving.common;

/**
 * 지표 조회 응답의 원소 (AdminConsole.showMetrics → MetricsCollector.query).
 *
 * @param name  지표 이름
 * @param value 값
 * @param at    측정 시각 (epoch 밀리초)
 */
public record MetricResponse(
        String name,
        double value,
        long at) {
}
