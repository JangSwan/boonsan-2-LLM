package com.llmserving.worker.adapter;

/** 다른 앱이 응답하지 않음 (모든 주소에서 연결 실패, 타임아웃, 503). 다음 주기에 다시 시도한다. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
