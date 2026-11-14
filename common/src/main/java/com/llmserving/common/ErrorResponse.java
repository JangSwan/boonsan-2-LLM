package com.llmserving.common;

/**
 * 모든 앱의 공통 에러 응답. 각 앱의 @RestControllerAdvice가 만든다.
 *
 * @param code       에러 코드
 * @param message    설명 (한국어)
 * @param requestId  관련 요청 ID (없으면 null)
 * @param instanceId 응답한 인스턴스 ID
 * @param timestamp  발생 시각 (epoch ms)
 */
public record ErrorResponse(
        ErrorCode code,
        String message,
        String requestId,
        String instanceId,
        long timestamp) {
}
