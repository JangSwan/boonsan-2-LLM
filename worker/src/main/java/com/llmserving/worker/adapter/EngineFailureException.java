package com.llmserving.worker.adapter;

/** 추론 엔진 비정상 종료 또는 응답 없음. 진행 중이던 요청은 엔진에서 사라진다. */
public class EngineFailureException extends RuntimeException {

    public EngineFailureException(String message) {
        super(message);
    }

    public EngineFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
