package com.llmserving.membership.adapter;

/**
 * AWS 스팟 인스턴스 재생성 포트 (Replenisher). real: recovery.SpotInstanceClient (EA), mock: MockSpotInstanceClient.
 */
public interface SpotInstancePort {

    /** 대체 인스턴스를 요청하고 인스턴스 ID를 돌려준다. 같은 키의 두 번째 요청은 같은 결과를 준다. */
    String requestInstance(String idempotencyKey);
}
