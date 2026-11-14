package com.llmserving.broker.replication;

/**
 * broker 인스턴스의 현재 역할. BROKER_ROLE은 시작할 때의 값이고, 승격·fencing 후의 역할은 메모리에서 관리한다.
 */
public enum BrokerRole {
    /** 접수, 큐, lease를 처리하는 주 브로커. */
    PRIMARY,
    /** 동기 복제를 받는 대기 복제본. 승격 전에는 접수·인출에 503으로 응답한다. */
    REPLICA,
    /** 더 높은 epoch의 주 브로커가 있어 물러난 옛 주 브로커. 쓰기를 하지 않는다. */
    FENCED
}
