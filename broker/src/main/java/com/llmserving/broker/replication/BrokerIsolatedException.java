package com.llmserving.broker.replication;

import com.llmserving.broker.config.ApiException;
import com.llmserving.common.ErrorCode;

/** 복제본과 연락이 끊기고 broker-primary 락도 갱신하지 못한 고립 상태 (sd5 규칙 3). 쓰기를 멈춘다. 503. */
public class BrokerIsolatedException extends ApiException {

    public BrokerIsolatedException() {
        super(ErrorCode.BROKER_ISOLATED, "복제본 확인도 broker-primary 락도 없어 쓰기를 멈췄습니다");
    }
}
