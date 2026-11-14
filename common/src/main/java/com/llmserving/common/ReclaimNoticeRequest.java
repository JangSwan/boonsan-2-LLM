package com.llmserving.common;

/**
 * 스팟 회수 통지 (AWS → Membership NoticeHandler).
 * fake-aws의 시연 명령도 같은 모양으로 받는다.
 *
 * @param workerId 회수 예정 워커 ID
 */
public record ReclaimNoticeRequest(String workerId) {
}
