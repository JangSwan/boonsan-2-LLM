package com.llmserving.common;

/**
 * 스팟 회수 통지 본문 (fake-aws → membership NoticeController → NoticeHandler.onReclaimNotice).
 *
 * @param workerId 회수 예정 워커 ID
 */
public record ReclaimNoticeRequest(String workerId) {
}
