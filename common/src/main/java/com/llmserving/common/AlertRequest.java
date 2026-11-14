package com.llmserving.common;

/**
 * 장애, 복구 알림 등록 (Membership RecoveryNotifier → Monitoring AlertService).
 *
 * @param kind    알림 종류 (예: SERVICE_RESTORED, REPLENISHED)
 * @param message 알림 내용
 * @param at      발생 시각 (epoch ms)
 */
public record AlertRequest(
        String kind,
        String message,
        long at) {
}
