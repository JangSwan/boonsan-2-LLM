package com.llmserving.frontgateway.adapter;

import com.llmserving.common.Request;

/**
 * broker AdmissionController 호출 포트 (enqueue, 순번 조회). 구현: frontgateway.QueueBrokerClient (EA).
 * 주 브로커가 응답하지 않으면 구현이 복제본 주소로 전환한다 (sd5 12번).
 */
public interface AdmissionPort {

    /** 적재하고 대기 순번을 돌려준다. 복제가 확인된 뒤 응답이 온다. */
    int enqueue(Request request);

    /** 대기 순번. 이미 인출되었으면 0. */
    int getPosition(String requestId);
}
