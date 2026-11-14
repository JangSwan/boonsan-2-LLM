package com.llmserving.broker.retry;

/**
 * 격리된 요청의 실패 통지 포트 (EA FailureNotifier, 인터페이스). Front Gateway에 코드 의존하지 않는다.
 * 구현은 broker.adapter.FailureNotifierRestAdapter가 HTTP로 front-gateway StreamRelay를 부른다.
 */
public interface FailureNotifier {

    void notifyFailure(String requestId);
}
