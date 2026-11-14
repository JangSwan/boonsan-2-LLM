package com.llmserving.common;

/**
 * 인스턴스 기동 완료 본문 (fake-aws → membership NoticeController → Replenisher.onInstanceReady).
 * 두 Membership 인스턴스 모두 받고 리더만 처리한다.
 *
 * @param instanceId 기동한 인스턴스 ID. 이후 하트비트의 workerId와 같다
 */
public record InstanceReadyRequest(String instanceId) {
}
