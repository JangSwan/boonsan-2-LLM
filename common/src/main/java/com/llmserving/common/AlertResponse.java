package com.llmserving.common;

/**
 * 알림 조회 응답의 원소 (AdminConsole.showAlerts → AlertService.getAlerts).
 *
 * @param kind    알림 종류
 * @param message 알림 내용
 * @param at      발생 시각 (epoch 밀리초)
 */
public record AlertResponse(
        String kind,
        String message,
        long at) {
}
