package com.llmserving.common;

/**
 * 사다리 단계 정보 (model-manager LadderStage를 앱 사이로 옮기는 DTO).
 *
 * @param name       단계 이름 (예: U0, FP8, SMALL)
 * @param precision  정밀도
 * @param weightUri  가중치 위치
 * @param kvGbPerGpu GPU당 KV 용량 (GB)
 */
public record StageResponse(
        String name,
        String precision,
        String weightUri,
        double kvGbPerGpu) {
}
