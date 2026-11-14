package com.llmserving.broker.adapter;

/** 복제본이 응답하지 않음 (연결 실패, 타임아웃, 5xx). */
public class ReplicaUnavailableException extends RuntimeException {

    public ReplicaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
