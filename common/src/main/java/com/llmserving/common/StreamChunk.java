package com.llmserving.common;

/**
 * 워커가 밀어 보내는 토큰 조각 (EA Common.StreamChunk, entity).
 * Front Gateway의 DuplicateFilter가 요청 ID와 순번으로 중복을 거른다.
 *
 * @param requestId 요청 ID
 * @param seq       요청 안에서의 순번 (0부터)
 * @param token     토큰 문자열
 * @param isFirst   첫 토큰 여부 (TTFT 기준)
 * @param isLast    마지막 조각 여부. Front Gateway가 SSE를 닫는 근거
 */
public record StreamChunk(
        String requestId,
        int seq,
        String token,
        boolean isFirst,
        boolean isLast) {
}
