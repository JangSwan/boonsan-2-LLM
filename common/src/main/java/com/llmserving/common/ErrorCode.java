package com.llmserving.common;

/**
 * 에러 응답 코드 (CLAUDE.md 5절 "에러 응답").
 * httpStatus는 각 앱의 @RestControllerAdvice가 응답 상태로 쓴다.
 */
public enum ErrorCode {

    // 400: 입력값이 잘못됨
    INVALID_INPUT(400),

    // 404: 없는 요청·lease·워커
    REQUEST_NOT_FOUND(404),
    LEASE_NOT_FOUND(404),
    WORKER_NOT_FOUND(404),
    STAGE_NOT_FOUND(404),

    // 409: 상태 충돌
    LEASE_NOT_OWNED(409),
    STALE_EPOCH(409),
    ALREADY_PROCESSED(409),
    QUALITY_GATE_FAILED(409),

    // 503: 지금 처리할 수 없음
    NOT_LEADER(503),
    REPLICA_NOT_PROMOTED(503),
    BROKER_ISOLATED(503),
    UPSTREAM_UNAVAILABLE(503),

    // SSE 실패 이벤트: 재시도 상한에 닿아 격리된 요청 (sd4)
    REQUEST_QUARANTINED(500),

    // 그 밖의 예상하지 못한 오류
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
