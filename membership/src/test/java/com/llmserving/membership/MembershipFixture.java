package com.llmserving.membership;

import com.llmserving.common.AlertRequest;
import com.llmserving.common.WorkerStatus;
import com.llmserving.membership.adapter.LeasePort;
import com.llmserving.membership.adapter.LockStorePort;
import com.llmserving.membership.adapter.SpotInstancePort;
import com.llmserving.membership.adapter.WorkerControlPort;
import com.llmserving.membership.config.MembershipProperties;
import com.llmserving.membership.detection.HealthDetector;
import com.llmserving.membership.detection.MemberRegistry;
import com.llmserving.membership.detection.NoticeHandler;
import com.llmserving.membership.leader.LeaderLock;
import com.llmserving.membership.recovery.LeaseReclaimer;
import com.llmserving.membership.recovery.RecoveryNotifier;
import com.llmserving.membership.recovery.Replenisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** membership 부품을 Spring 없이 엮는 테스트 도구. */
public class MembershipFixture {

    public final MutableClock clock;
    public final MembershipProperties properties;
    public final MemberRegistry registry;
    public final LeaderLock leaderLock;
    public final RecoveryNotifier recoveryNotifier;
    public final LeaseReclaimer leaseReclaimer;
    public final Replenisher replenisher;
    public final HealthDetector healthDetector;
    public final NoticeHandler noticeHandler;
    public final List<String> reclaimed = Collections.synchronizedList(new ArrayList<>());
    public final List<String> instanceRequests = Collections.synchronizedList(new ArrayList<>());
    public final List<String> stopPulls = Collections.synchronizedList(new ArrayList<>());
    public final List<AlertRequest> alerts = Collections.synchronizedList(new ArrayList<>());

    public MembershipFixture(String instanceId, MutableClock clock, FakeLockStore lockStore) {
        this.clock = clock;
        this.properties = new MembershipProperties(instanceId, 3, 1000, "membership-leader", 5, 1500, 1,
                List.of("http://b1"), List.of("http://worker-5:8095"), "http://mon", "http://aws",
                new MembershipProperties.Aws("ap-northeast-2", ""));
        this.registry = new MemberRegistry(clock);
        this.leaderLock = new LeaderLock(lockStore.portFor(clock), clock, properties);
        this.recoveryNotifier = new RecoveryNotifier(alerts::add, clock);
        LeasePort leasePort = workerId -> {
            reclaimed.add(workerId);
            return 2;
        };
        this.leaseReclaimer = new LeaseReclaimer(leasePort, recoveryNotifier, properties);
        SpotInstancePort spot = key -> {
            instanceRequests.add(key);
            return "worker-spare";
        };
        this.replenisher = new Replenisher(spot, recoveryNotifier);
        this.healthDetector = new HealthDetector(registry, leaderLock, leaseReclaimer, replenisher, properties);
        WorkerControlPort control = stopPulls::add;
        this.noticeHandler = new NoticeHandler(control, healthDetector);
    }

    public static WorkerStatus status(String workerId, boolean selfTestPassed) {
        return new WorkerStatus(workerId, "v1", "PULLING", 0, selfTestPassed);
    }

    /** 리더 락 흉내 (Redis 키 하나에 만료 시각). */
    public static class FakeLockStore {
        private final Map<String, String> holders = new HashMap<>();
        private final Map<String, Long> expiries = new HashMap<>();

        public LockStorePort portFor(Clock clock) {
            return new LockStorePort() {
                @Override
                public boolean tryAcquire(String key, String holderId, long ttlMs) {
                    synchronized (FakeLockStore.this) {
                        Long exp = expiries.get(key);
                        if (exp == null || clock.millis() >= exp) {
                            holders.put(key, holderId);
                            expiries.put(key, clock.millis() + ttlMs);
                            return true;
                        }
                        return false;
                    }
                }

                @Override
                public boolean renew(String key, String holderId, long ttlMs) {
                    synchronized (FakeLockStore.this) {
                        Long exp = expiries.get(key);
                        if (holderId.equals(holders.get(key)) && exp != null && clock.millis() < exp) {
                            expiries.put(key, clock.millis() + ttlMs);
                            return true;
                        }
                        return false;
                    }
                }
            };
        }
    }

    public static class MutableClock extends Clock {
        private volatile long millis = 1_000_000L;

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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
