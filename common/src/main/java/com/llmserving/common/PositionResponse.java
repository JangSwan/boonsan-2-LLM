package com.llmserving.common;

/**
 * 순번 조회 응답 (Broker → Front Gateway).
 *
 * @param requestId 요청 ID
 * @param position  현재 대기 순번
 */
public record PositionResponse(
        String requestId,
        int position) {
}
