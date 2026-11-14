package com.llmserving.common;

/**
 * 모델 교체 요청 (AdminConsole.requestModelSwap → ModelSwapController.requestSwap(model, stage)).
 *
 * @param model 새 모델 이름
 * @param stage 사다리 단계 이름
 */
public record SwapRequest(
        String model,
        String stage) {
}
