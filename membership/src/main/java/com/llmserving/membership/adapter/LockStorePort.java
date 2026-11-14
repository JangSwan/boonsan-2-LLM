package com.llmserving.membership.adapter;

/**
 * Redis 리더 락 저장소. Redis에 닿지 못하면 false (락이 없는 것으로 본다: 아무도 조치하지 않는 쪽이 안전하다).
 */
public interface LockStorePort {

    /** 비어 있을 때만 얻는다. */
    boolean tryAcquire(String key, String holderId, long ttlMs);

    /** 내 락일 때만 TTL을 갱신한다. */
    boolean renew(String key, String holderId, long ttlMs);
}
