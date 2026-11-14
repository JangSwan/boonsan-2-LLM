package com.llmserving.monitoring;

/**
 * 알림 한 건 (EA AlertEvent, entity).
 *
 * @param kind    알림 종류
 * @param message 내용
 * @param at      발생 시각 (epoch 밀리초)
 */
public record AlertEvent(String kind, String message, long at) {
}
