package com.llmserving.client.apiclient;

/** 질문 API 실패 (접수 실패, 실패 이벤트). */
public class ApiClientException extends RuntimeException {

    public ApiClientException(String message) {
        super(message);
    }
}
