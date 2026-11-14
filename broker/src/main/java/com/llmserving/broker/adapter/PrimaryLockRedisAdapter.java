package com.llmserving.broker.adapter;

import com.llmserving.broker.config.BrokerProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * PrimaryLockPort의 Redis 구현. 큐·lease 데이터는 Redis에 넣지 않는다 (CLAUDE.md 2절).
 */
@Slf4j
@Component
public class PrimaryLockRedisAdapter implements PrimaryLockPort {

    private static final DefaultRedisScript<Long> RENEW_OR_ACQUIRE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if not current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String key;

    public PrimaryLockRedisAdapter(StringRedisTemplate redis, BrokerProperties properties) {
        this.redis = redis;
        this.key = properties.primaryLockKey();
    }

    @Override
    public boolean tryAcquire(String holderId, long ttlMs) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, holderId, ttlMs, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.warn("event=lock.unreachable key={} msg=Redis에 닿지 못해 락을 얻지 못함: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean renewOrAcquire(String holderId, long ttlMs) {
        try {
            Long result = redis.execute(RENEW_OR_ACQUIRE, List.of(key), holderId, String.valueOf(ttlMs));
            return result != null && result == 1L;
        } catch (RuntimeException e) {
            log.warn("event=lock.unreachable key={} msg=Redis에 닿지 못해 락을 갱신하지 못함: {}", key, e.getMessage());
            return false;
        }
    }
}
