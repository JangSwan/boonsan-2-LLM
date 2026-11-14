package com.llmserving.worker.adapter;

/** broker에 lease가 없음 (이미 만료·회수·완료됨). 더 갱신하지 않는다. */
public class LeaseGoneException extends RuntimeException {

    public LeaseGoneException(String requestId) {
        super("lease가 없습니다: " + requestId);
    }
}
