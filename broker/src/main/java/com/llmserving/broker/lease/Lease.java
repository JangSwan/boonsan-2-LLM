package com.llmserving.broker.lease;

import com.llmserving.broker.queue.QueueItem;
import lombok.Getter;

/**
 * 실행 중 작업 1건의 점유권 (EA Lease, entity). 상태 변경은 extend()로만 한다.
 */
@Getter
public class Lease {

    private final String leaseId;
    private final String requestId;
    private final String workerId;
    private volatile long expiresAt;
    /** 점유한 작업. 워커에 본문을 넘기고, 만료 시 RetryCounter, 회수 시 requeue에 넘긴다. */
    private final QueueItem item;

    public Lease(String leaseId, String workerId, long expiresAt, QueueItem item) {
        this.leaseId = leaseId;
        this.requestId = item.requestId();
        this.workerId = workerId;
        this.expiresAt = expiresAt;
        this.item = item;
    }

    /** 만료 시각 연장 (sd4 7번). */
    public void extend(long newExpiresAt) {
        this.expiresAt = newExpiresAt;
    }

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }
}
