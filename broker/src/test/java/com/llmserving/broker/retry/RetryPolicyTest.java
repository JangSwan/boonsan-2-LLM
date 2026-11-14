package com.llmserving.broker.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.broker.BrokerFixture;
import com.llmserving.broker.queue.QueueItem;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(BrokerFixture.standalone().properties); // 상한 2

    @Test
    void 재시도_횟수가_상한_미만이면_다시_시도한다() {
        QueueItem item = BrokerFixture.item("x", "v1");
        item.increaseRetry();
        assertThat(policy.shouldRetry(item)).isTrue();
    }

    @Test
    void 상한에_닿으면_다시_시도하지_않는다() {
        QueueItem item = BrokerFixture.item("x", "v1");
        item.increaseRetry();
        item.increaseRetry();
        assertThat(policy.shouldRetry(item)).isFalse();
    }
}
