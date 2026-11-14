package com.llmserving.common;

/**
 * 질문 1건 (EA Common::Request, entity).
 * 요청 ID와 버전 태그로 큐, lease, 스트림을 잇는다.
 *
 * @param requestId   요청 ID (UUID). Front Gateway만 발급한다
 * @param versionTag  버전 태그 (v1 / v2)
 * @param inputTokens 입력 토큰 수
 * @param createdAt   접수 시각 (epoch ms)
 * @param prompt      질문 본문. 실제 엔진에 넘길 입력 (mock은 길이만 사용)
 */
public record Request(
        String requestId,
        String versionTag,
        int inputTokens,
        long createdAt,
        String prompt) {
}
