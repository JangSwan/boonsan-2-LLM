package com.llmserving.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.broker.adapter.PrimaryLockPort;
import com.llmserving.broker.adapter.ReplicaPort;
import com.llmserving.broker.adapter.ReplicaUnavailableException;
import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.lease.LeaseLimiter;
import com.llmserving.broker.lease.LeaseManager;
import com.llmserving.broker.queue.QuarantineQueue;
import com.llmserving.broker.queue.QueueItem;
import com.llmserving.broker.queue.SharedQueue;
import com.llmserving.broker.replication.BrokerReplica;
import com.llmserving.broker.replication.ChangeRecord;
import com.llmserving.broker.replication.Replicator;
import com.llmserving.broker.retry.FailureNotifier;
import com.llmserving.broker.retry.RetryCounter;
import com.llmserving.broker.retry.RetryPolicy;
import com.llmserving.common.ReplicationRequest;
import com.llmserving.common.ReplicationResponse;
import com.llmserving.common.Request;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * broker 부품을 Spring 없이 엮는 테스트 도구. 가짜 시계, 가짜 Redis 락, 메모리 복제 포트를 쓴다.
 */
public class BrokerFixture {

    public final MutableClock clock;
    public final BrokerProperties properties;
    public final BrokerReplica brokerReplica;
    public final Replicator replicator;
    public final SharedQueue sharedQueue;
    public final QuarantineQueue quarantineQueue;
    public final LeaseLimiter leaseLimiter;
    public final LeaseManager leaseManager;
    public final RecordingFailureNotifier failureNotifier = new RecordingFailureNotifier();
    public final SwitchableReplicaPort replicaPort = new SwitchableReplicaPort();

    public BrokerFixture(String instanceId, boolean primary, boolean withReplica, MutableClock clock, FakeLock lock) {
        this.clock = clock;
        this.properties = new BrokerProperties(instanceId, primary ? "primary" : "replica", 10, 4, 2, 1000,
                "broker-primary", 1000, 3, 5, withReplica ? "http://replica" : "", "http://front", 1);
        ObjectMapper objectMapper = new ObjectMapper();
        Ref<SharedQueue> sq = new Ref<>();
        Ref<QuarantineQueue> qq = new Ref<>();
        Ref<LeaseManager> lm = new Ref<>();
        this.brokerReplica = new BrokerReplica(objectMapper, lock.portFor(clock), clock, properties, sq, qq, lm);
        this.replicator = new Replicator(brokerReplica, replicaPort, lock.portFor(clock), objectMapper, clock,
                properties, sq, qq, lm);
        this.sharedQueue = new SharedQueue(replicator);
        this.quarantineQueue = new QuarantineQueue(replicator);
        this.leaseLimiter = new LeaseLimiter(properties);
        RetryCounter retryCounter = new RetryCounter(new RetryPolicy(properties), sharedQueue, quarantineQueue,
                failureNotifier);
        this.leaseManager = new LeaseManager(sharedQueue, leaseLimiter, retryCounter, replicator, clock, properties);
        sq.value = sharedQueue;
        qq.value = quarantineQueue;
        lm.value = leaseManager;
    }

    /** 복제본 없이 혼자 도는 주 브로커 (락은 항상 얻을 수 있음). */
    public static BrokerFixture standalone() {
        MutableClock clock = new MutableClock();
        BrokerFixture fixture = new BrokerFixture("broker-primary", true, false, clock, new FakeLock());
        fixture.replicator.maintain();
        return fixture;
    }

    /** 시간을 진행하고, 주 브로커라면 그 사이 락 갱신 주기를 돈다. */
    public void advance(long ms) {
        for (long t = 0; t < ms; t += 1000) {
            clock.advanceMs(Math.min(1000, ms - t));
            replicator.maintain();
        }
    }

    public static QueueItem item(String requestId, String versionTag) {
        return new QueueItem(new Request(requestId, versionTag, 100, 0, "질문 " + requestId), 0, 0);
    }

    // ---- 테스트 도구 ----

    public static class MutableClock extends Clock {
        private long millis = 1_000_000L;

        public void advanceMs(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** Redis broker-primary 락 흉내: 키 하나, 소유자와 만료 시각. */
    public static class FakeLock {
        private String holder;
        private long expiresAt;
        public boolean reachable = true;

        public PrimaryLockPort portFor(Clock clock) {
            return new PrimaryLockPort() {
                @Override
                public boolean tryAcquire(String holderId, long ttlMs) {
                    synchronized (FakeLock.this) {
                        if (!reachable) {
                            return false;
                        }
                        if (holder == null || clock.millis() >= expiresAt) {
                            holder = holderId;
                            expiresAt = clock.millis() + ttlMs;
                            return true;
                        }
                        return false;
                    }
                }

                @Override
                public boolean renewOrAcquire(String holderId, long ttlMs) {
                    synchronized (FakeLock.this) {
                        if (!reachable) {
                            return false;
                        }
                        if (holderId.equals(holder) && clock.millis() < expiresAt) {
                            expiresAt = clock.millis() + ttlMs;
                            return true;
                        }
                        return tryAcquire(holderId, ttlMs);
                    }
                }
            };
        }

        public synchronized String holder() {
            return holder;
        }
    }

    /** 복제 대상을 바꿀 수 있는 ReplicaPort: 다른 BrokerFixture의 BrokerReplica로 보내거나, 응답 없음을 흉내 낸다. */
    public static class SwitchableReplicaPort implements ReplicaPort {
        public volatile BrokerReplica target;
        public volatile boolean down;
        public final List<String> kinds = new ArrayList<>();

        @Override
        public ReplicationResponse send(ReplicationRequest request) {
            if (down || target == null) {
                throw new ReplicaUnavailableException("복제본 내려감", null);
            }
            target.apply(new ChangeRecord(request.seq(), request.kind(), request.payload(), request.epoch()));
            synchronized (kinds) {
                kinds.add(request.kind());
            }
            return new ReplicationResponse(request.seq(), target.epoch(), true);
        }
    }

    public static class RecordingFailureNotifier implements FailureNotifier {
        public final List<String> failed = new ArrayList<>();

        @Override
        public synchronized void notifyFailure(String requestId) {
            failed.add(requestId);
        }
    }

    static class Ref<T> implements ObjectProvider<T> {
        T value;

        @Override
        public T getObject() throws BeansException {
            return value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }
    }
}
