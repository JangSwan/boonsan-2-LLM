package com.llmserving.broker.lease;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.broker.BrokerFixture;
import org.junit.jupiter.api.Test;

class LeaseLimiterTest {

    private final LeaseLimiter limiter = BrokerFixture.standalone().leaseLimiter; // 상한 4

    @Test
    void 상한_안에서는_요청한_만큼_허용한다() {
        assertThat(limiter.allow(3)).isEqualTo(3);
        assertThat(limiter.getActiveLeases()).isEqualTo(3);
    }

    @Test
    void 상한을_넘는_요청은_남은_만큼만_허용한다() {
        limiter.allow(3);
        assertThat(limiter.allow(5)).isEqualTo(1);
        assertThat(limiter.allow(1)).isZero();
        assertThat(limiter.getActiveLeases()).isEqualTo(4);
    }

    @Test
    void 반환하면_다시_허용한다() {
        limiter.allow(4);
        limiter.release();
        assertThat(limiter.allow(2)).isEqualTo(1);
    }

    @Test
    void 영_이하_요청과_과다_반환은_무시한다() {
        assertThat(limiter.allow(0)).isZero();
        assertThat(limiter.allow(-1)).isZero();
        limiter.release();
        limiter.release(10);
        assertThat(limiter.getActiveLeases()).isZero();
    }

    @Test
    void 승격_시_실제_lease_수로_맞춘다() {
        limiter.resetTo(3);
        assertThat(limiter.allow(4)).isEqualTo(1);
    }
}
