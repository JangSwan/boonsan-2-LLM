package com.llmserving.membership.recovery;

import com.llmserving.common.AlertRequest;
import com.llmserving.membership.adapter.AlertPort;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * T서비스, T재보충 기록과 운영자 알림 (EA RecoveryNotifier, R3). Monitoring AlertService로 장애·복구 알림을 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryNotifier {

    public static final String KIND_FAILURE = "worker.failure";
    public static final String KIND_SERVICE_RECOVERED = "recovery.service";
    public static final String KIND_REPLENISHED = "recovery.replenished";

    private final AlertPort alertPort;
    private final Clock clock;
    private final Map<String, RecoveryRecord> records = new ConcurrentHashMap<>();

    /** 장애 감지(제외) 시각을 기록하고 장애 알림을 보낸다. LeaseReclaimer가 회수를 시작할 때 부른다. */
    public void recordFailure(String workerId) {
        RecoveryRecord record = new RecoveryRecord(workerId, clock.millis());
        records.put(workerId, record);
        raise(KIND_FAILURE, workerId + " 하트비트 끊김, 제외 후 lease 회수 시작");
    }

    /** 재분배 완료 (sd3 12~13번): T서비스를 기록하고 복구 알림을 보낸다. */
    public void onRedistributed(String workerId) {
        RecoveryRecord record = records.computeIfAbsent(workerId, id -> new RecoveryRecord(id, clock.millis()));
        record.redistributed(clock.millis());
        log.info("event=recovery.service workerId={} tServiceMs={} msg=재분배 완료, 남은 워커가 4대분 처리",
                workerId, record.getTServiceMs());
        raise(KIND_SERVICE_RECOVERED, workerId + " 장애 후 처리 능력 복구 (T서비스 " + record.getTServiceMs() + "ms)");
    }

    /** 합류 완료 (sd3 21~22번): T재보충을 기록한다. workerId는 장애가 났던 워커다. */
    public void onJoined(String workerId) {
        RecoveryRecord record = records.computeIfAbsent(workerId, id -> new RecoveryRecord(id, clock.millis()));
        record.replenished(clock.millis());
        log.info("event=recovery.replenished workerId={} tReplenishMs={} msg=대체 서버 합류, 다시 5대",
                workerId, record.getTReplenishMs());
        raise(KIND_REPLENISHED, workerId + " 대체 서버 합류, 다시 5대 (T재보충 " + record.getTReplenishMs() + "ms)");
    }

    public RecoveryRecord recordOf(String workerId) {
        return records.get(workerId);
    }

    private void raise(String kind, String message) {
        try {
            alertPort.raise(new AlertRequest(kind, message, clock.millis()));
        } catch (RuntimeException e) {
            log.warn("event=alert.failed kind={} msg=알림 전송 실패: {}", kind, e.getMessage());
        }
    }
}
