package com.llmserving.broker.retry;

import com.llmserving.broker.queue.QuarantineQueue;
import com.llmserving.broker.queue.QueueItem;
import com.llmserving.broker.queue.SharedQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 만료 횟수를 세어 재투입 또는 격리 (EA RetryCounter, R3 보완).
 * 엔진을 죽이는 요청이 워커를 연달아 죽이지 않도록 재시도 상한으로 영향 범위를 제한한다 (sd4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryCounter {

    private final RetryPolicy retryPolicy;
    private final SharedQueue sharedQueue;
    private final QuarantineQueue quarantineQueue;
    private final FailureNotifier failureNotifier;

    public void onExpired(QueueItem item) {
        item.increaseRetry();
        if (retryPolicy.shouldRetry(item)) {
            sharedQueue.requeue(item);
            log.info("event=retry.requeued requestId={} retryCount={} retryLimit={} msg=재시도 상한 미만, 공용 큐에 재투입",
                    item.requestId(), item.getRetryCount(), retryPolicy.getRetryLimit());
            return;
        }
        quarantineQueue.isolate(item);
        log.warn("event=retry.quarantined requestId={} retryCount={} retryLimit={} msg=재시도 상한 도달, 격리 후 실패 통지",
                item.requestId(), item.getRetryCount(), retryPolicy.getRetryLimit());
        failureNotifier.notifyFailure(item.requestId());
    }
}
