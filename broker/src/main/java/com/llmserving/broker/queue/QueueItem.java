package com.llmserving.broker.queue;

import com.llmserving.common.Request;
import lombok.Getter;

/**
 * 큐에 들어간 요청과 재시도 횟수 (EA QueueItem, entity).
 * 상태 변경은 increaseRetry()로만 한다.
 */
@Getter
public class QueueItem {

    private final Request request;
    private final long enqueuedAt;
    private int retryCount;

    public QueueItem(Request request, long enqueuedAt, int retryCount) {
        this.request = request;
        this.enqueuedAt = enqueuedAt;
        this.retryCount = retryCount;
    }

    /** lease 만료 1회를 센다 (sd4 11번). */
    public synchronized void increaseRetry() {
        retryCount++;
    }

    public synchronized int getRetryCount() {
        return retryCount;
    }

    public String requestId() {
        return request.requestId();
    }

    public String versionTag() {
        return request.versionTag();
    }
}
