package com.llmserving.worker.scheduling;

/**
 * 엔진 이벤트 구독 포트 (EA EngineEventListener, 인터페이스). 크레딧과 lease 갱신이 엔진에 직접 묶이지 않게 한다
 * (T3 단점 3 보완). 구현: CreditManager, LeaseRenewer.
 */
public interface EngineEventListener {

    /** 첫 토큰. unstartedTokens는 이 워커에 남은 미시작 Prefill 토큰 수. */
    void onFirstToken(String requestId, int unstartedTokens);

    /** 스텝에 포함되어 진척이 있었음. */
    void onProgress(String requestId);

    /** 요청 생성 완료 (StepResult.finishedIds). */
    void onCompleted(String requestId);
}
