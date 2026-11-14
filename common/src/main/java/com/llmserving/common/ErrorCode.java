package com.llmserving.common;

/**
 * 에러 응답 코드 (CLAUDE.md 5절 "에러 응답"). 괄호는 HTTP 상태 코드다.
 */
public enum ErrorCode {
    /** 입력값이 잘못됨 (400) */
    INVALID_INPUT,
    /** 없는 요청·lease·워커 (404) */
    NOT_FOUND,
    /** 내 lease가 아님, 이미 처리됨 (409) */
    CONFLICT,
    /** 낡은 epoch·펜싱 토큰 (409) */
    STALE_EPOCH,
    /** 리더가 아님 (503) */
    NOT_LEADER,
    /** 승격 전 복제본, 고립된 주 브로커 (503) */
    NOT_PRIMARY,
    /** 대상 서비스 응답 없음 (503) */
    UNAVAILABLE,
    /** 예상하지 못한 오류 (500) */
    INTERNAL
}
