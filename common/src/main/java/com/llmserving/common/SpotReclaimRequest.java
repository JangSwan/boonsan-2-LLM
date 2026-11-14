package com.llmserving.common;

/**
 * 시연 명령 본문 (POST /api/fake-aws/spot-reclaims). fake-aws가 Membership에 회수 통지를 보낸다.
 *
 * @param workerId 회수할 워커 ID
 */
public record SpotReclaimRequest(String workerId) {
}
