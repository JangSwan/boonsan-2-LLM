package com.llmserving.frontgateway.config;

import com.llmserving.common.ErrorCode;
import com.llmserving.common.ErrorResponse;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 예외를 common의 ErrorResponse로 바꿔 응답한다 (CLAUDE.md 5절 "에러 응답").
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final FrontGatewayProperties properties;
    private final Clock clock;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return respond(e.getCode(), e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadInput(Exception e) {
        return respond(ErrorCode.INVALID_INPUT, "입력값이 잘못되었습니다: " + e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e) {
        log.error("event=error.unexpected msg=예상하지 못한 오류", e);
        return respond(ErrorCode.INTERNAL_ERROR, "내부 오류: " + e.getMessage(), null);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, String requestId) {
        String rid = requestId != null ? requestId : MDC.get(MdcRequestFilter.MDC_REQUEST_ID);
        ErrorResponse body = new ErrorResponse(code, message, rid, properties.instanceId(), clock.millis());
        // SSE 입구에서 난 예외도 JSON으로 응답한다.
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
