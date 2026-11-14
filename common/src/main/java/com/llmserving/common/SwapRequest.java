package com.llmserving.common;

/**
 * 모델 교체 요청 (Web UI AdminConsole → Model Manager ModelSwapController).
 *
 * @param model 새 모델 이름
 * @param stage 사다리 단계 이름 (예: U0, FP8, SMALL)
 */
public record SwapRequest(
        String model,
        String stage) {
}
