package com.llmserving.worker.scheduling;

import java.util.List;
import java.util.Map;

/**
 * 한 스텝에 넣을 Decode와 Prefill 조각 (EA StepPlan, entity).
 * Decode 1건은 토큰 1개로 센다. decodeIds.size() + prefillChunks 합계 ≤ budget (Decode가 B보다 많으면 Decode만 넣는다).
 * Prefill 조각 0은 "스케줄러 기준으로 남은 입력이 없음"을 뜻한다.
 *
 * @param decodeIds     Decode 중인 요청 전부
 * @param prefillChunks 요청 ID → 이번 스텝에 읽을 Prefill 토큰 수
 * @param budget        스텝 예산 B
 * @param prompts       이번 스텝에서 Prefill을 처음 시작하는 요청의 본문 (요청 ID → 본문)
 */
public record StepPlan(
        List<String> decodeIds,
        Map<String, Integer> prefillChunks,
        int budget,
        Map<String, String> prompts) {

    public boolean isEmpty() {
        return decodeIds.isEmpty() && prefillChunks.isEmpty();
    }

    public int prefillTokens() {
        return prefillChunks.values().stream().mapToInt(Integer::intValue).sum();
    }
}
