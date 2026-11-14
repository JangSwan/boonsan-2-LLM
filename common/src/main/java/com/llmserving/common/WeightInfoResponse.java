package com.llmserving.common;

/**
 * 단계별 가중치 정보 (Model Manager → Worker ModelRuntime 가중치 적재).
 *
 * @param stage      단계 이름
 * @param precision  정밀도 (예: BF16, FP8)
 * @param weightUri  가중치 위치
 * @param sizeGb     가중치 크기 (GB)
 * @param kvGbPerGpu GPU당 KV 용량 (GB)
 */
public record WeightInfoResponse(
        String stage,
        String precision,
        String weightUri,
        double sizeGb,
        double kvGbPerGpu) {
}
