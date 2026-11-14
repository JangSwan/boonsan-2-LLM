package com.llmserving.fakeaws.adapter;

/** membership 통지 포트 (AWS → Membership: 회수 통지 · 기동 완료). 두 인스턴스 모두에 보낸다. */
public interface MembershipNoticePort {

    void reclaimNotice(String workerId);

    void instanceReady(String instanceId);
}
