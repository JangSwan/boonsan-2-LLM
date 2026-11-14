package com.llmserving.broker.replication;

import com.llmserving.broker.config.ApiException;
import com.llmserving.common.ErrorCode;

/** 주 브로커가 아니어서 접수·인출을 받을 수 없음 (승격 전 복제본, fencing된 옛 주 브로커). 503. */
public class NotPrimaryException extends ApiException {

    public NotPrimaryException(BrokerRole role) {
        super(ErrorCode.REPLICA_NOT_PROMOTED, "주 브로커가 아닙니다 (현재 역할: " + role + ")");
    }
}
