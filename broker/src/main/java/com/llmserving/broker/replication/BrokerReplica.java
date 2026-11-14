package com.llmserving.broker.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.broker.adapter.PrimaryLockPort;
import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.lease.LeaseManager;
import com.llmserving.broker.queue.QuarantineQueue;
import com.llmserving.broker.queue.SharedQueue;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기 복제본 (EA BrokerReplica, C2 보완). 주 브로커 소실 시 쿼럼 기반으로 승격하고 옛 주 브로커를 fencing한다.
 * 복제본 쪽 규칙(sd5 규칙 4~5): 주 브로커와 연락이 끊긴 지 X초가 지났고 broker-primary 락을 얻었을 때만 승격하며,
 * 승격할 때 epoch를 1 올린다. 현재보다 낮은 epoch의 복제 메시지는 거부한다.
 * 이 인스턴스의 현재 역할과 epoch도 여기서 관리한다 (BROKER_ROLE은 시작할 때의 역할).
 */
@Slf4j
@Component
public class BrokerReplica {

    private final ObjectMapper objectMapper;
    private final PrimaryLockPort primaryLockPort;
    private final Clock clock;
    private final BrokerProperties properties;
    private final ObjectProvider<SharedQueue> sharedQueue;
    private final ObjectProvider<QuarantineQueue> quarantineQueue;
    private final ObjectProvider<LeaseManager> leaseManager;
    private final long promoteAfterMs;
    private final long lockTtlMs;

    private volatile BrokerRole role;
    private volatile long epoch = 1;
    private volatile long lastPrimaryContactAt;
    /** 주 브로커에게서 한 번이라도 복제 메시지를 받았는가. 받기 전에는 "연락이 끊겼다"고 보지 않는다 (동시 기동 시 오승격 방지). */
    private volatile boolean primarySeen;
    private volatile boolean promoted;
    private volatile long fencedUpToEpoch;

    public BrokerReplica(ObjectMapper objectMapper, PrimaryLockPort primaryLockPort, Clock clock,
                         BrokerProperties properties, ObjectProvider<SharedQueue> sharedQueue,
                         ObjectProvider<QuarantineQueue> quarantineQueue, ObjectProvider<LeaseManager> leaseManager) {
        this.objectMapper = objectMapper;
        this.primaryLockPort = primaryLockPort;
        this.clock = clock;
        this.properties = properties;
        this.sharedQueue = sharedQueue;
        this.quarantineQueue = quarantineQueue;
        this.leaseManager = leaseManager;
        this.promoteAfterMs = properties.promoteAfterSec() * 1000L;
        this.lockTtlMs = properties.primaryLockTtlSec() * 1000L;
        this.role = properties.startsAsPrimary() ? BrokerRole.PRIMARY : BrokerRole.REPLICA;
        this.lastPrimaryContactAt = clock.millis();
        log.info("event=broker.started role={} epoch={} msg=broker 시작", role, epoch);
    }

    /** 주 브로커의 변경을 적용한다 (sd5 4번). 낮은 epoch는 거부한다 (fencing). */
    public synchronized void apply(ChangeRecord change) {
        if (change.epoch() < epoch || role != BrokerRole.REPLICA) {
            throw new StaleEpochException(change.epoch(), epoch);
        }
        epoch = change.epoch();
        lastPrimaryContactAt = clock.millis();
        primarySeen = true;
        if (ChangeRecord.BATCH.equals(change.kind())) {
            read(change.payload(), ChangePayloads.Batch.class).entries()
                    .forEach(entry -> applyOne(entry.kind(), entry.payload()));
        } else {
            applyOne(change.kind(), change.payload());
        }
    }

    private void applyOne(String kind, String payload) {
        switch (kind) {
            case ChangeRecord.PING -> { }
            case ChangeRecord.QUEUE_PUSHED -> sharedQueue.getObject().applyPushed(read(payload, ChangePayloads.ItemData.class).toItem());
            case ChangeRecord.QUEUE_REQUEUED -> sharedQueue.getObject().applyRequeued(read(payload, ChangePayloads.ItemData.class).toItem());
            case ChangeRecord.QUEUE_REMOVED -> sharedQueue.getObject().applyRemoved(read(payload, ChangePayloads.RequestIds.class).requestIds());
            case ChangeRecord.LEASES_ISSUED -> leaseManager.getObject().applyIssued(
                    read(payload, ChangePayloads.Leases.class).leases().stream().map(ChangePayloads.LeaseData::toLease).toList());
            case ChangeRecord.LEASE_RENEWED -> {
                ChangePayloads.LeaseRenewal renewal = read(payload, ChangePayloads.LeaseRenewal.class);
                leaseManager.getObject().applyRenewed(renewal.leaseId(), renewal.expiresAt());
            }
            case ChangeRecord.LEASE_REMOVED -> leaseManager.getObject().applyRemoved(read(payload, ChangePayloads.LeaseIdData.class).leaseId());
            case ChangeRecord.QUARANTINED -> quarantineQueue.getObject().applyIsolated(read(payload, ChangePayloads.ItemData.class).toItem());
            case ChangeRecord.SNAPSHOT -> restore(read(payload, ChangePayloads.Snapshot.class));
            default -> throw new IllegalArgumentException("알 수 없는 변경 종류: " + kind);
        }
    }

    /** 주 브로커 응답 끊김 감지 (sd5 8번): 복제본이고, 주 브로커와 연락하던 중 마지막 연락 후 X초가 지났는가. */
    public boolean detectPrimaryLoss() {
        return role == BrokerRole.REPLICA && primarySeen && clock.millis() - lastPrimaryContactAt >= promoteAfterMs;
    }

    /** 주기 작업: 주 브로커 응답 감시. 끊겼고 broker-primary 락을 얻으면 승격한다. */
    @Scheduled(fixedDelayString = "${llm.broker.primary-lock-renew-interval-ms}")
    public void watchPrimary() {
        if (!detectPrimaryLoss()) {
            return;
        }
        if (primaryLockPort.tryAcquire(properties.instanceId(), lockTtlMs)) {
            promote();
        } else {
            log.debug("event=broker.promote-waiting msg=주 브로커 락이 아직 유효해 승격하지 않음");
        }
    }

    /** 새 주 브로커로 승격 (sd5 9번). 큐와 lease는 그대로 유지한다. */
    public synchronized void promote() {
        if (role != BrokerRole.REPLICA) {
            return;
        }
        epoch = epoch + 1;
        role = BrokerRole.PRIMARY;
        promoted = true;
        leaseManager.getObject().onPromoted();
        log.warn("event=broker.promoted epoch={} queue={} leases={} msg=주 브로커 응답 끊김, 복제본을 주 브로커로 승격",
                epoch, sharedQueue.getObject().length(), leaseManager.getObject().activeCount());
        fenceOldPrimary();
    }

    /** 옛 주 브로커 차단: 이후 옛 epoch가 붙은 복제 메시지는 apply()에서 거부한다. */
    public void fenceOldPrimary() {
        fencedUpToEpoch = epoch - 1;
        log.warn("event=broker.fenced fencedUpToEpoch={} msg=옛 주 브로커의 낮은 epoch 메시지 거부", fencedUpToEpoch);
    }

    /** 더 높은 epoch의 주 브로커가 있음을 알았을 때 물러난다 (옛 주 브로커 쪽). */
    public synchronized void stepDown(long newerEpoch) {
        if (role == BrokerRole.FENCED) {
            return;
        }
        role = BrokerRole.FENCED;
        log.error("event=broker.stepped-down epoch={} newerEpoch={} msg=더 높은 epoch의 주 브로커가 있어 쓰기 중지",
                epoch, newerEpoch);
    }

    public boolean isPrimary() {
        return role == BrokerRole.PRIMARY;
    }

    public boolean isPromoted() {
        return promoted;
    }

    public BrokerRole role() {
        return role;
    }

    public long epoch() {
        return epoch;
    }

    private void restore(ChangePayloads.Snapshot snapshot) {
        sharedQueue.getObject().restore(snapshot.queue().stream().map(ChangePayloads.ItemData::toItem).toList());
        quarantineQueue.getObject().restore(snapshot.quarantined().stream().map(ChangePayloads.ItemData::toItem).toList());
        leaseManager.getObject().restore(snapshot.leases().stream().map(ChangePayloads.LeaseData::toLease).toList());
        log.info("event=replication.snapshot-applied queue={} leases={} msg=주 브로커 전체 상태 적용",
                snapshot.queue().size(), snapshot.leases().size());
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("변경 내용 해석 실패: " + type.getSimpleName(), e);
        }
    }
}
