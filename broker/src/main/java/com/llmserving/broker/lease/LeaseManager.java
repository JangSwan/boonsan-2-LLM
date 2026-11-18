package com.llmserving.broker.lease;

import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.queue.QueueItem;
import com.llmserving.broker.queue.SharedQueue;
import com.llmserving.broker.replication.ChangePayloads;
import com.llmserving.broker.replication.ChangeRecord;
import com.llmserving.broker.replication.Replicator;
import com.llmserving.broker.retry.RetryCounter;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pull, lease 발급, 갱신, 완료, 만료, 회수 (EA LeaseManager, C2).
 * 호출자: Worker CreditManager(pull, complete), LeaseRenewer(renew), Membership LeaseReclaimer(reclaim).
 * 잠금 순서: LeaseManager → SharedQueue/QuarantineQueue → Replicator (역순 호출 없음).
 */
@Slf4j
@Component
public class LeaseManager {

    private final SharedQueue sharedQueue;
    private final LeaseLimiter leaseLimiter;
    private final RetryCounter retryCounter;
    private final Replicator replicator;
    private final Clock clock;
    private final long leaseTtlMs;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    public LeaseManager(SharedQueue sharedQueue, LeaseLimiter leaseLimiter, RetryCounter retryCounter,
                        Replicator replicator, Clock clock, BrokerProperties properties) {
        this.sharedQueue = sharedQueue;
        this.leaseLimiter = leaseLimiter;
        this.retryCounter = retryCounter;
        this.replicator = replicator;
        this.clock = clock;
        this.leaseTtlMs = properties.leaseTtlSec() * 1000L;
    }

    /**
     * 워커가 계산한 크레딧 n만큼 요청한다. LeaseLimiter가 허용한 k(≤ n)건만 버전 일치 작업으로 내준다 (sd1 13~18번).
     */
    public synchronized List<Lease> pull(String workerId, String versionTag, int n) {
        replicator.ensurePrimary();
        int k = leaseLimiter.allow(n);
        if (k == 0) {
            return List.of();
        }
        List<Lease> issued;
        try {
            // 큐에서 제거와 lease 발급을 복제 메시지 하나로 커밋한다 (중간에 주 브로커가 죽어도 요청이 사라지지 않게).
            issued = replicator.transaction(() -> {
                List<QueueItem> items = sharedQueue.pullMatching(versionTag, k);
                long expiresAt = clock.millis() + leaseTtlMs;
                List<Lease> leasesToIssue = items.stream()
                        .map(item -> new Lease(UUID.randomUUID().toString(), workerId, expiresAt, item))
                        .toList();
                if (!leasesToIssue.isEmpty()) {
                    replicator.commit(ChangeRecord.LEASES_ISSUED, ChangePayloads.Leases.of(leasesToIssue),
                            () -> leasesToIssue.forEach(lease -> leases.put(lease.getLeaseId(), lease)));
                }
                return leasesToIssue;
            });
        } catch (RuntimeException e) {
            leaseLimiter.release(k);
            throw e;
        }
        leaseLimiter.release(k - issued.size());
        for (Lease lease : issued) {
            try (var ignored = MDC.putCloseable("requestId", lease.getRequestId())) {
                log.info("event=lease.issued requestId={} leaseId={} workerId={} versionTag={} expiresAt={} msg=lease 발급",
                        lease.getRequestId(), lease.getLeaseId(), workerId, versionTag, lease.getExpiresAt());
            }
        }
        return issued;
    }

    /** 공용 큐에 남은 같은 버전 작업 수. pull 응답에 실어 보낸다 (sd6 마지막 워커의 드레인 종료 판단). */
    public int remainingFor(String versionTag) {
        return sharedQueue.countMatching(versionTag);
    }

    /** 진척이 보고된 요청의 lease 만료 시각을 연장한다 (sd4 6~7번). */
    public synchronized Lease renew(String leaseId) {
        replicator.ensurePrimary();
        Lease lease = find(leaseId);
        long newExpiresAt = clock.millis() + leaseTtlMs;
        replicator.commit(ChangeRecord.LEASE_RENEWED, new ChangePayloads.LeaseRenewal(leaseId, newExpiresAt),
                () -> lease.extend(newExpiresAt));
        log.debug("event=lease.renewed requestId={} leaseId={} expiresAt={}", lease.getRequestId(), leaseId, newExpiresAt);
        return lease;
    }

    /** 완료 ack. lease를 지우고 크레딧(동시 실행 수)을 반환한다 (sd1 24번). */
    public synchronized Lease complete(String leaseId) {
        replicator.ensurePrimary();
        Lease lease = find(leaseId);
        removeCommitted(lease);
        try (var ignored = MDC.putCloseable("requestId", lease.getRequestId())) {
            log.info("event=lease.completed requestId={} leaseId={} workerId={} msg=완료 ack",
                    lease.getRequestId(), leaseId, lease.getWorkerId());
        }
        return lease;
    }

    /** 갱신이 끊겨 만료된 lease를 RetryCounter에 보고한다. 바로 큐에 넣지 않는다 (sd4 10~11번). */
    @Scheduled(fixedDelayString = "${llm.broker.expire-check-interval-ms}")
    public synchronized void expireOverdue() {
        if (!replicator.isPrimary()) {
            return;
        }
        long now = clock.millis();
        List<Lease> expired = leases.values().stream().filter(l -> l.isExpired(now)).toList();
        for (Lease lease : expired) {
            try (var ignored = MDC.putCloseable("requestId", lease.getRequestId())) {
                log.warn("event=lease.expired requestId={} leaseId={} workerId={} retryCount={} msg=갱신이 끊겨 lease 만료",
                        lease.getRequestId(), lease.getLeaseId(), lease.getWorkerId(), lease.getItem().getRetryCount());
                // lease 제거와 재투입·격리를 함께 커밋한다.
                replicator.transaction(() -> {
                    removeCommitted(lease);
                    retryCounter.onExpired(lease.getItem());
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("event=lease.expire-failed leaseId={} msg=만료 처리 실패, 다음 주기에 다시 시도: {}",
                        lease.getLeaseId(), e.getMessage());
            }
        }
    }

    /**
     * 제외된 워커의 lease를 회수해 미완료 요청을 공용 큐로 되돌린다 (sd3 9~10번).
     * 서버 장애 회수이므로 RetryCounter를 거치지 않고 재시도 횟수도 올리지 않는다. 멱등.
     */
    public synchronized int reclaim(String workerId) {
        replicator.ensurePrimary();
        List<Lease> owned = leases.values().stream().filter(l -> l.getWorkerId().equals(workerId)).toList();
        for (Lease lease : owned) {
            try (var ignored = MDC.putCloseable("requestId", lease.getRequestId())) {
                replicator.transaction(() -> {
                    removeCommitted(lease);
                    sharedQueue.requeue(lease.getItem());
                    return null;
                });
                log.info("event=lease.reclaimed requestId={} leaseId={} workerId={} msg=회수해 공용 큐로 반환",
                        lease.getRequestId(), lease.getLeaseId(), workerId);
            }
        }
        return owned.size();
    }

    public long currentEpoch() {
        return replicator.currentEpoch();
    }

    public int activeCount() {
        return leases.size();
    }

    /** 스냅샷용 읽기. */
    public Collection<Lease> leases() {
        return List.copyOf(leases.values());
    }

    // ---- 복제본 쪽 적용 (BrokerReplica에서만 호출) ----

    public void applyIssued(List<Lease> issued) {
        issued.forEach(lease -> leases.put(lease.getLeaseId(), lease));
    }

    public void applyRenewed(String leaseId, long expiresAt) {
        Lease lease = leases.get(leaseId);
        if (lease != null) {
            lease.extend(expiresAt);
        }
    }

    public void applyRemoved(String leaseId) {
        leases.remove(leaseId);
    }

    public synchronized void restore(List<Lease> snapshot) {
        leases.clear();
        snapshot.forEach(lease -> leases.put(lease.getLeaseId(), lease));
    }

    /**
     * 승격 직후: 기존 lease를 그대로 이어받는다 (sd5 11번). 승격하는 동안 워커가 갱신하지 못했으므로
     * 만료 시각을 TTL만큼 다시 주고, 동시 실행 수를 실제 lease 수로 맞춘다.
     */
    public synchronized void onPromoted() {
        long expiresAt = clock.millis() + leaseTtlMs;
        leases.values().forEach(lease -> lease.extend(expiresAt));
        leaseLimiter.resetTo(leases.size());
        log.info("event=lease.inherited count={} msg=승격 후 기존 lease 유지", leases.size());
    }

    private Lease find(String leaseId) {
        Lease lease = leases.get(leaseId);
        if (lease == null) {
            throw new LeaseNotFoundException(leaseId);
        }
        return lease;
    }

    private void removeCommitted(Lease lease) {
        replicator.commit(ChangeRecord.LEASE_REMOVED, new ChangePayloads.LeaseIdData(lease.getLeaseId()), () -> {
            if (leases.remove(lease.getLeaseId()) != null) {
                leaseLimiter.release();
            }
        });
    }
}
