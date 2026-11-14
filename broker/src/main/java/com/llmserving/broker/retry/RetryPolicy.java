package com.llmserving.broker.retry;

import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.queue.QueueItem;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 재시도 상한 판정 (EA RetryPolicy). 상한은 broker-config의 retryLimit.
 * 만료 횟수(retryCount)가 상한 미만이면 다시 시도한다. 상한 2면 첫 만료는 재투입, 두 번째 만료는 격리 (sd4).
 */
@Getter
@Component
public class RetryPolicy {

    private final int retryLimit;

    public RetryPolicy(BrokerProperties properties) {
        this.retryLimit = properties.retryLimit();
    }

    public boolean shouldRetry(QueueItem item) {
        return item.getRetryCount() < retryLimit;
    }
}
