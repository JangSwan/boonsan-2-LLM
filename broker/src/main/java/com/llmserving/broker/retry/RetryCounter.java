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

    /**
     * 만료 1회를 세고 재투입하거나 격리한다. 늘린 횟수는 사본에 담아 넘기고 원본(lease가 쥔 작업)은 바꾸지 않는다:
     * 고립 등으로 커밋에 실패하면 lease가 그대로 남아 다음 주기에 다시 만료 처리되는데, 그때 같은 만료를 두 번 세지 않게 한다.
     */
    public void onExpired(QueueItem item) {
        QueueItem retried = new QueueItem(item.getRequest(), item.getEnqueuedAt(), item.getRetryCount());
        retried.increaseRetry();
        if (retryPolicy.shouldRetry(retried)) {
            sharedQueue.requeue(retried);
            log.info("event=retry.requeued requestId={} retryCount={} retryLimit={} msg=재시도 상한 미만, 공용 큐에 재투입",
                    retried.requestId(), retried.getRetryCount(), retryPolicy.getRetryLimit());
            return;
        }
        quarantineQueue.isolate(retried);
        log.warn("event=retry.quarantined requestId={} retryCount={} retryLimit={} msg=재시도 상한 도달, 격리 후 실패 통지",
                retried.requestId(), retried.getRetryCount(), retryPolicy.getRetryLimit());
        failureNotifier.notifyFailure(retried.requestId());
    }
}
