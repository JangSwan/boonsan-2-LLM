package com.llmserving.common;

/**
 * 큐 지표 응답 (Monitoring QueueMetricsPort → broker QueueMetricsController → SharedQueue).
 *
 * @param queueLength      공용 큐 길이
 * @param quarantinedCount 격리 큐 건수
 * @param activeLeases     현재 동시 실행 수
 * @param concurrencyCap   동시 실행 상한 (4대분)
 * @param collectedAt      측정 시각 (epoch 밀리초)
 * @param shortLength      짧은 요청 줄 길이 (유형별 인출이 꺼져 있으면 queueLength와 같다)
 * @param longLength       긴 요청 줄 길이 (유형별 인출이 꺼져 있으면 0)
 */
public record QueueMetricsResponse(
        int queueLength,
        int quarantinedCount,
        int activeLeases,
        int concurrencyCap,
        long collectedAt,
        int shortLength,
        int longLength) {
}
