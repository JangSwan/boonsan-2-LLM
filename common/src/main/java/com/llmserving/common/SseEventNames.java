package com.llmserving.common;

/**
 * Front Gateway가 클라이언트로 보내는 SSE 이벤트 이름.
 */
public final class SseEventNames {

    /** 대기 순번 안내. data: PositionResponse */
    public static final String POSITION = "position";
    /** 토큰 조각. data: StreamChunk */
    public static final String TOKEN = "token";
    /** 실패 통지 (격리된 요청). data: ErrorResponse */
    public static final String FAILURE = "failure";
    /** 스트림 종료 (마지막 조각 뒤). data: 요청 ID */
    public static final String DONE = "done";

    private SseEventNames() {
    }
}
