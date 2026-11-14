package com.llmserving.common;

/**
 * 대체 인스턴스 요청 응답. 같은 idempotencyKey면 처음 배정한 instanceId를 그대로 돌려준다.
 *
 * @param instanceId     배정된 인스턴스 ID (mock: worker-spare)
 * @param idempotencyKey 요청에 쓴 중복 방지 키
 */
public record InstanceResponse(
        String instanceId,
        String idempotencyKey) {
}
