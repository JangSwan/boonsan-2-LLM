package com.llmserving.common;

/**
 * 대체 인스턴스 요청 응답 (AWS → Membership).
 *
 * @param instanceId 새 인스턴스 ID. 이후 하트비트의 workerId와 같다
 */
public record InstanceResponse(String instanceId) {
}
