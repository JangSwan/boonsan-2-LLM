package com.llmserving.frontgateway.adapter;

import com.llmserving.common.ErrorCode;
import com.llmserving.frontgateway.config.ApiException;

/** 다른 앱이 응답하지 않음 (모든 주소에서 실패). 503. */
public class UpstreamUnavailableException extends ApiException {

    public UpstreamUnavailableException(String message) {
        super(ErrorCode.UPSTREAM_UNAVAILABLE, message);
    }
}
