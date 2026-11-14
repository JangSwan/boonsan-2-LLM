package com.llmserving.membership.leader;

import com.llmserving.membership.adapter.LockStorePort;
import com.llmserving.membership.config.MembershipProperties;
import java.time.Clock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 제어기 2개 중 락을 가진 1개만 조치 (EA LeaderLock, R3 보완).
 * isLeader()는 마지막으로 락을 얻거나 갱신한 시각 + TTL 전까지만 true다 (Redis 응답이 끊기면 스스로 물러난다).
 */
@Slf4j
@Getter
@Component
public class LeaderLock {

    private final String holderId;
    private volatile long expiresAt;
    private final LockStorePort lockStore;
    private final Clock clock;
    private final String key;
    private final long ttlMs;

    public LeaderLock(LockStorePort lockStore, Clock clock, MembershipProperties properties) {
        this.lockStore = lockStore;
        this.clock = clock;
        this.holderId = properties.instanceId();
        this.key = properties.lockKey();
        this.ttlMs = properties.lockTtlSec() * 1000L;
    }

    public boolean tryAcquire() {
        long start = clock.millis();
        if (lockStore.tryAcquire(key, holderId, ttlMs)) {
            expiresAt = start + ttlMs;
            log.info("event=leader.acquired holderId={} msg=리더 락 획득", holderId);
            return true;
        }
        return false;
    }

    public boolean isLeader() {
        return clock.millis() < expiresAt;
    }

    /** 주기 작업: 리더면 갱신하고, 아니면 비어 있을 때 얻는다. */
    @Scheduled(fixedDelayString = "${llm.membership.lock-renew-interval-ms}")
    public void renew() {
        if (isLeader()) {
            long start = clock.millis();
            if (lockStore.renew(key, holderId, ttlMs)) {
                expiresAt = start + ttlMs;
                return;
            }
            expiresAt = 0;
            log.warn("event=leader.lost holderId={} msg=리더 락 갱신 실패, 조치 중단", holderId);
        }
        tryAcquire();
    }
}
