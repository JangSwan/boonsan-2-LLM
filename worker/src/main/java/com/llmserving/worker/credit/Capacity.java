package com.llmserving.worker.credit;

/**
 * 남은 슬롯, KV, 미시작 토큰 (EA Capacity, entity). EngineScheduler.freeCapacity()의 응답.
 *
 * @param freeSlots       남은 슬롯
 * @param freeKvGb        남은 KV 용량 (GB)
 * @param unstartedTokens 미시작 Prefill 토큰 수
 */
public record Capacity(int freeSlots, double freeKvGb, int unstartedTokens) {
}
