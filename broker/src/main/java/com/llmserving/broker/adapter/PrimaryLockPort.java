package com.llmserving.broker.adapter;

/**
 * Redis broker-primary 락 (쿼럼의 세 번째 표, sd5). Replicator와 BrokerReplica만 쓴다.
 * Redis에 닿지 못하면 예외 대신 false를 돌려준다 (락을 갖지 못한 것으로 본다).
 */
public interface PrimaryLockPort {

    /** 비어 있을 때만 얻는다. 다른 인스턴스의 락이 만료되기 전에는 false. */
    boolean tryAcquire(String holderId, long ttlMs);

    /** 내 락이면 TTL을 갱신하고, 비어 있으면 얻는다. 다른 인스턴스가 갖고 있으면 false. */
    boolean renewOrAcquire(String holderId, long ttlMs);
}
