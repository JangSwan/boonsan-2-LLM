package com.llmserving.common;

/**
 * 알림 한 건 조회 결과 (Monitoring → Web UI AdminConsole).
 *
 * @param kind    알림 종류
 * @param message 알림 내용
 * @param at      발생 시각 (epoch ms)
 */
public record AlertResponse(
        String kind,
        String message,
        long at) {
}
