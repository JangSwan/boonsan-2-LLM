package com.llmserving.common;

/**
 * 세션, GPU, TTFT 수집 결과 (Worker → Monitoring).
 *
 * @param workerId       워커 ID
 * @param activeSessions 진행 중 세션 수
 * @param gpuUtilization GPU 사용률 (0.0 ~ 1.0, mock은 추정값)
 * @param ttftP95Ms      최근 첫 토큰 시간 p95 (ms)
 * @param at             수집 시각 (epoch ms)
 */
public record WorkerMetricsResponse(
        String workerId,
        int activeSessions,
        double gpuUtilization,
        double ttftP95Ms,
        long at) {
}
