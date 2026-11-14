package com.llmserving.common;

/**
 * 가중치 적재 응답. mock은 단계별 가짜 가중치 정보(크기, KV 용량)를 돌려준다.
 *
 * @param stageName  단계 이름
 * @param weightUri  가중치 위치
 * @param sizeGb     가중치 크기 (GB)
 * @param kvGbPerGpu GPU당 KV 용량 (GB)
 */
public record WeightLoadResponse(
        String stageName,
        String weightUri,
        double sizeGb,
        double kvGbPerGpu) {
}
