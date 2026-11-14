package com.llmserving.common;

/**
 * 알림 발생 본문 (RecoveryNotifier → AlertPort → monitoring AlertController → AlertService.raise).
 *
 * @param kind    알림 종류 (예: worker.excluded, recovery.service, recovery.replenished)
 * @param message 알림 내용 (한국어)
 * @param at      발생 시각 (epoch 밀리초)
 */
public record AlertRequest(
        String kind,
        String message,
        long at) {
}
