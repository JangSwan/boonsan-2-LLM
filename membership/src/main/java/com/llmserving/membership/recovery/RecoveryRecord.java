package com.llmserving.membership.recovery;

import lombok.Getter;

/**
 * 장애 1건의 복구 시간 기록 (EA RecoveryRecord, entity).
 * T서비스 = 감지(제외) → 재분배 완료, T재보충 = 감지 → 대체 서버 합류.
 */
@Getter
public class RecoveryRecord {

    private final String workerId;
    private final long detectedAt;
    private volatile int tServiceSec = -1;
    private volatile int tReplenishSec = -1;
    private volatile long tServiceMs = -1;
    private volatile long tReplenishMs = -1;

    public RecoveryRecord(String workerId, long detectedAt) {
        this.workerId = workerId;
        this.detectedAt = detectedAt;
    }

    public void redistributed(long at) {
        tServiceMs = at - detectedAt;
        tServiceSec = (int) Math.ceil(tServiceMs / 1000.0);
    }

    public void replenished(long at) {
        tReplenishMs = at - detectedAt;
        tReplenishSec = (int) Math.ceil(tReplenishMs / 1000.0);
    }
}
