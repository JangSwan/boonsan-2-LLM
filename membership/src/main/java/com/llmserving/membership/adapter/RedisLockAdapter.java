package com.llmserving.membership.adapter;

import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** LockStorePort의 Redis 구현. Redis는 락에만 쓴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockAdapter implements LockStorePort {

    private static final DefaultRedisScript<Long> RENEW_IF_MINE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(String key, String holderId, long ttlMs) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, holderId, ttlMs, TimeUnit.MILLISECONDS));
        } catch (RuntimeException e) {
            log.warn("event=lock.unreachable key={} msg=Redis에 닿지 못함: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean renew(String key, String holderId, long ttlMs) {
        try {
            Long result = redis.execute(RENEW_IF_MINE, List.of(key), holderId, String.valueOf(ttlMs));
            return result != null && result == 1L;
        } catch (RuntimeException e) {
            log.warn("event=lock.unreachable key={} msg=Redis에 닿지 못함: {}", key, e.getMessage());
            return false;
        }
    }
}
