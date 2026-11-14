package com.llmserving.broker.lease;

import com.llmserving.broker.config.ApiException;
import com.llmserving.common.ErrorCode;

/** 없는 lease (이미 완료, 만료, 회수됨). 404. */
public class LeaseNotFoundException extends ApiException {

    public LeaseNotFoundException(String leaseId) {
        super(ErrorCode.LEASE_NOT_FOUND, "lease가 없습니다: " + leaseId);
    }
}
