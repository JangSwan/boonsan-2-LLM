package com.llmserving.common;

/**
 * 질문 1건 (EA Common.Request, entity). 요청 ID와 버전 태그로 큐, lease, 스트림을 잇는다.
 * enqueue 본문이자 lease 응답에 실려 워커로 전달된다.
 *
 * @param requestId   요청 ID (UUID, Front Gateway만 발급)
 * @param versionTag  버전 태그 (v1 / v2)
 * @param inputTokens 입력 토큰 수
 * @param createdAt   생성 시각 (epoch 밀리초)
 * @param prompt      질문 본문. 실제 엔진에 넘길 입력 (mock은 길이만 사용)
 */
public record Request(
        String requestId,
        String versionTag,
        int inputTokens,
        long createdAt,
        String prompt) {
}
