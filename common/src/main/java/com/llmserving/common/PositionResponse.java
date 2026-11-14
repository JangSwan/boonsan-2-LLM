package com.llmserving.common;

/**
 * 순번 조회 응답 (AdmissionController.getPosition). SSE position 이벤트 데이터로도 쓴다.
 *
 * @param requestId 요청 ID
 * @param position  대기 순번 (1부터). 이미 인출되어 큐에 없으면 0
 * @param epoch     응답한 주 브로커의 epoch (sd5 fencing)
 */
public record PositionResponse(
        String requestId,
        int position,
        long epoch) {
}
