package com.llmserving.common;

/**
 * 모든 앱의 공통 에러 응답 (CLAUDE.md 5절). SSE 실패 이벤트의 데이터로도 쓴다.
 *
 * @param code       에러 코드
 * @param message    사람이 읽는 설명 (한국어)
 * @param requestId  관련 요청 ID (없으면 null)
 * @param instanceId 응답한 인스턴스 ID (INSTANCE_ID)
 * @param timestamp  발생 시각 (epoch 밀리초)
 */
public record ErrorResponse(
        ErrorCode code,
        String message,
        String requestId,
        String instanceId,
        long timestamp) {
}
