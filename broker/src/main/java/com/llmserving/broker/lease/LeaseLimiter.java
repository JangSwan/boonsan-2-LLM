package com.llmserving.broker.lease;

import com.llmserving.broker.config.BrokerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 전체 동시 실행을 4대분 상한으로 제한 (EA LeaseLimiter, R3).
 * 5대가 일해도 동시 실행은 4대분까지만 허용해, 1대가 사라져도 남은 4대가 같은 양을 처리한다.
 */
@Slf4j
@Component
public class LeaseLimiter {

    private final int concurrencyCap;
    private int activeLeases;

    public LeaseLimiter(BrokerProperties properties) {
        this.concurrencyCap = properties.concurrencyCap();
    }

    /** 상한 확인과 허용 수 증가를 한 번에 한다 (sd1 14~15번). 허용 k는 0 이상 requested 이하. */
    public synchronized int allow(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int k = Math.max(0, Math.min(requested, concurrencyCap - activeLeases));
        activeLeases += k;
        return k;
    }

    /** lease 1건 종료 (완료, 만료, 회수). */
    public synchronized void release() {
        if (activeLeases > 0) {
            activeLeases--;
        }
    }

    /** 허용했지만 쓰지 않은 수를 돌려준다. */
    public synchronized void release(int count) {
        activeLeases = Math.max(0, activeLeases - count);
    }

    /** 승격 시 실제 lease 수로 맞춘다 (복제본은 상한 계산을 하지 않고 lease만 받아 두므로). */
    public synchronized void resetTo(int active) {
        activeLeases = Math.max(0, active);
    }

    public synchronized int getActiveLeases() {
        return activeLeases;
    }

    public int getConcurrencyCap() {
        return concurrencyCap;
    }
}
