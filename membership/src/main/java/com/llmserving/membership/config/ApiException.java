package com.llmserving.membership.config;

import com.llmserving.common.ErrorCode;
import lombok.Getter;

/**
 * ErrorResponse로 바뀌는 예외의 공통 부모. GlobalExceptionHandler가 code의 HTTP 상태로 응답한다.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String requestId;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, String requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }
}
