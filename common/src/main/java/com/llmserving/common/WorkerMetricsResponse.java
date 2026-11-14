package com.llmserving.common;

/**
 * 워커 지표 응답 (Monitoring WorkerMetricsPort → worker WorkerController → EngineScheduler).
 * 세션 · GPU · TTFT 수집.
 *
 * @param workerId        워커 ID
 * @param versionTag      인출 중인 버전 태그
 * @param activeSessions  진행 중 요청 수
 * @param freeSlots       남은 슬롯
 * @param freeKvGb        남은 KV 용량 (GB)
 * @param unstartedTokens 미시작 Prefill 토큰 수
 * @param stepBudget      최근 스텝 예산 B
 * @param ttftP95Ms       최근 첫 토큰 시간 p95 (밀리초)
 * @param gpuUtilization  GPU 사용률 (0.0 ~ 1.0, mock은 추정값)
 * @param collectedAt     측정 시각 (epoch 밀리초)
 */
public record WorkerMetricsResponse(
        String workerId,
        String versionTag,
        int activeSessions,
        int freeSlots,
        double freeKvGb,
        int unstartedTokens,
        int stepBudget,
        long ttftP95Ms,
        double gpuUtilization,
        long collectedAt) {
}
