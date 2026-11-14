package com.llmserving.fakeaws.adapter;

/** 예비 워커 기동 신호 포트 (대체 인스턴스가 만들어진 것을 흉내 낸다). */
public interface WorkerBootPort {

    void boot(String instanceUrl);
}
