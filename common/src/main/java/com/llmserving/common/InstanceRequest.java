package com.llmserving.common;

/**
 * 대체 인스턴스 요청 (Membership Replenisher → AWS).
 *
 * @param idempotencyKey 중복 방지 키 (workerId 기반). 같은 키의 두 번째 요청은 무시된다
 */
public record InstanceRequest(String idempotencyKey) {
}
