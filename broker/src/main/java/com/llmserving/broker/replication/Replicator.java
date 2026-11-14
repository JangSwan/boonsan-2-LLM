package com.llmserving.broker.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.broker.adapter.PrimaryLockPort;
import com.llmserving.broker.adapter.ReplicaPort;
import com.llmserving.broker.adapter.ReplicaUnavailableException;
import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.lease.LeaseManager;
import com.llmserving.broker.queue.QuarantineQueue;
import com.llmserving.broker.queue.SharedQueue;
import com.llmserving.common.ReplicationRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

/**
 * 큐와 lease 변경을 복제본에 동기 복제 (EA Replicator, C2 보완). 주 브로커 쪽 쿼럼 규칙(sd5 규칙 1~3)을 여기서 지킨다.
 * <ol>
 *   <li>평소: 복제본의 확인을 받은 뒤 커밋한다 (Redis 없이도 동작).</li>
 *   <li>복제본과 연락이 끊기면: broker-primary 락을 갱신하고 있는 동안에만 혼자 커밋한다.</li>
 *   <li>복제본도 락도 없으면: 쓰기를 멈춘다 (고립).</li>
 * </ol>
 * 변경은 읽기 잠금 안에서 "복제 → 커밋"을 하고, 복제본 재동기화(스냅샷)는 쓰기 잠금 안에서 해 스냅샷이 일관되게 한다.
 * 함께 일어나야 하는 변경(인출 = 큐에서 제거 + lease 발급, 만료 = lease 제거 + 재투입·격리)은 transaction()으로 묶어
 * 복제 메시지 하나로 보낸다. 중간에 주 브로커가 죽어도 복제본에서 요청이 사라지지 않는다.
 */
@Slf4j
@Component
public class Replicator {

    private final BrokerReplica brokerReplica;
    private final ReplicaPort replicaPort;
    private final PrimaryLockPort primaryLockPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final BrokerProperties properties;
    private final ObjectProvider<SharedQueue> sharedQueue;
    private final ObjectProvider<QuarantineQueue> quarantineQueue;
    private final ObjectProvider<LeaseManager> leaseManager;

    private final AtomicLong seq = new AtomicLong();
    private final ThreadLocal<List<Pending>> batch = new ThreadLocal<>();
    private final ReentrantReadWriteLock commitLock = new ReentrantReadWriteLock();
    private final long lockTtlMs;
    private volatile boolean replicaAttached;
    private volatile long lockValidUntil;
    private volatile boolean isolatedLogged;

    public Replicator(BrokerReplica brokerReplica, ReplicaPort replicaPort, PrimaryLockPort primaryLockPort,
                      ObjectMapper objectMapper, Clock clock, BrokerProperties properties,
                      ObjectProvider<SharedQueue> sharedQueue, ObjectProvider<QuarantineQueue> quarantineQueue,
                      ObjectProvider<LeaseManager> leaseManager) {
        this.brokerReplica = brokerReplica;
        this.replicaPort = replicaPort;
        this.primaryLockPort = primaryLockPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.sharedQueue = sharedQueue;
        this.quarantineQueue = quarantineQueue;
        this.leaseManager = leaseManager;
        this.lockTtlMs = properties.primaryLockTtlSec() * 1000L;
        // 둘 다 빈 상태로 시작하므로 처음에는 동기화된 것으로 본다.
        this.replicaAttached = properties.hasReplica() && properties.startsAsPrimary();
    }

    /**
     * 변경 하나를 복제하고, 커밋해도 되면 true (EA 오퍼레이션). 커밋 가능 여부는 위 규칙 1~3으로 정한다.
     */
    public synchronized boolean replicate(ChangeRecord change) {
        ensurePrimary();
        if (replicaAttached) {
            try {
                if (replicaPort.send(toRequest(change)).applied()) {
                    return true;
                }
            } catch (StaleEpochException e) {
                brokerReplica.stepDown(e.getCurrentEpoch());
                throw new NotPrimaryException(BrokerRole.FENCED);
            } catch (ReplicaUnavailableException e) {
                replicaAttached = false;
                log.warn("event=replication.replica-detached seq={} msg=복제본 응답 없음, broker-primary 락으로 단독 커밋 전환: {}",
                        change.seq(), e.getMessage());
            }
        }
        if (lockHeld() || renewLockNow()) {
            isolatedLogged = false;
            return true;
        }
        if (!isolatedLogged) {
            isolatedLogged = true;
            log.error("event=broker.isolated msg=복제본 확인도 broker-primary 락도 없어 쓰기 중지");
        }
        return false;
    }

    /** 복제가 확인된 뒤에만 apply를 실행한다 (sd5 6번 "커밋"). 커밋할 수 없으면 503. */
    public void commit(String kind, Object payload, Runnable apply) {
        List<Pending> pending = batch.get();
        if (pending != null) {
            pending.add(new Pending(kind, toJson(payload), apply));
            return;
        }
        commitLock.readLock().lock();
        try {
            ChangeRecord change = new ChangeRecord(seq.incrementAndGet(), kind, toJson(payload), brokerReplica.epoch());
            if (!replicate(change)) {
                throw new BrokerIsolatedException();
            }
            apply.run();
        } finally {
            commitLock.readLock().unlock();
        }
    }

    /**
     * body 안의 commit()들을 복제 메시지 하나(BATCH)로 묶어 원자적으로 복제·커밋한다. body가 실패하면 아무것도 반영하지 않는다.
     * body 안에서는 apply가 바로 실행되지 않으므로, 같은 작업을 두 번 고르지 않도록 호출하는 쪽이 잠금을 잡는다.
     */
    public <T> T transaction(Supplier<T> body) {
        if (batch.get() != null) {
            return body.get();
        }
        commitLock.readLock().lock();
        List<Pending> pending = new ArrayList<>();
        batch.set(pending);
        try {
            T result = body.get();
            batch.remove();
            if (pending.isEmpty()) {
                return result;
            }
            List<ChangePayloads.Entry> entries = pending.stream()
                    .map(p -> new ChangePayloads.Entry(p.kind(), p.payload())).toList();
            ChangeRecord change = new ChangeRecord(seq.incrementAndGet(), ChangeRecord.BATCH,
                    toJson(new ChangePayloads.Batch(entries)), brokerReplica.epoch());
            if (!replicate(change)) {
                throw new BrokerIsolatedException();
            }
            pending.forEach(p -> p.apply().run());
            return result;
        } finally {
            batch.remove();
            commitLock.readLock().unlock();
        }
    }

    public void ensurePrimary() {
        if (!brokerReplica.isPrimary()) {
            throw new NotPrimaryException(brokerReplica.role());
        }
    }

    public boolean isPrimary() {
        return brokerReplica.isPrimary();
    }

    public long currentEpoch() {
        return brokerReplica.epoch();
    }

    /**
     * 주기 작업: broker-primary 락 갱신, 복제본에 생존 알림, 끊겼던 복제본 재동기화.
     */
    @Scheduled(fixedDelayString = "${llm.broker.primary-lock-renew-interval-ms}")
    public void maintain() {
        if (!brokerReplica.isPrimary()) {
            return;
        }
        long start = clock.millis();
        if (primaryLockPort.renewOrAcquire(properties.instanceId(), lockTtlMs)) {
            lockValidUntil = start + lockTtlMs;
        }
        if (!properties.hasReplica() || brokerReplica.isPromoted()) {
            return;
        }
        if (replicaAttached) {
            ping();
        } else {
            resync();
        }
    }

    private void ping() {
        try {
            send(new ChangeRecord(seq.get(), ChangeRecord.PING, toJson(new ChangePayloads.Empty()), brokerReplica.epoch()));
        } catch (ReplicaUnavailableException e) {
            replicaAttached = false;
            log.warn("event=replication.replica-detached msg=복제본 생존 확인 실패: {}", e.getMessage());
        }
    }

    /** 복제본이 다시 응답하면 전체 상태를 보내 맞춘 뒤 동기 복제를 다시 시작한다. */
    private void resync() {
        commitLock.writeLock().lock();
        try {
            ChangePayloads.Snapshot snapshot = new ChangePayloads.Snapshot(
                    sharedQueue.getObject().items().stream().map(ChangePayloads.ItemData::of).toList(),
                    quarantineQueue.getObject().list().stream().map(ChangePayloads.ItemData::of).toList(),
                    leaseManager.getObject().leases().stream().map(ChangePayloads.LeaseData::of).toList());
            send(new ChangeRecord(seq.incrementAndGet(), ChangeRecord.SNAPSHOT, toJson(snapshot), brokerReplica.epoch()));
            replicaAttached = true;
            log.info("event=replication.replica-attached queue={} leases={} msg=복제본 재동기화 후 동기 복제 재개",
                    snapshot.queue().size(), snapshot.leases().size());
        } catch (ReplicaUnavailableException e) {
            log.debug("event=replication.resync-failed msg=복제본 아직 응답 없음: {}", e.getMessage());
        } finally {
            commitLock.writeLock().unlock();
        }
    }

    private synchronized void send(ChangeRecord change) {
        try {
            replicaPort.send(toRequest(change));
        } catch (StaleEpochException e) {
            brokerReplica.stepDown(e.getCurrentEpoch());
        }
    }

    private boolean lockHeld() {
        return clock.millis() < lockValidUntil;
    }

    /** 복제본이 없을 때 락이 막 만료됐거나(갱신 주기 사이) 승격 직후라면 그 자리에서 한 번 갱신해 본다. */
    private boolean renewLockNow() {
        long start = clock.millis();
        if (primaryLockPort.renewOrAcquire(properties.instanceId(), lockTtlMs)) {
            lockValidUntil = start + lockTtlMs;
            return true;
        }
        return false;
    }

    private record Pending(String kind, String payload, Runnable apply) {
    }

    private ReplicationRequest toRequest(ChangeRecord change) {
        return new ReplicationRequest(change.seq(), change.kind(), change.payload(), change.epoch());
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("변경 내용 직렬화 실패", e);
        }
    }
}
