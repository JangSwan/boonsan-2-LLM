package com.llmserving.worker.budget;

import com.llmserving.worker.config.WorkerProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 예산 정책이 낸 B를 하한과 상한 사이로 자른다 (EA BudgetGuard, T3). 정책이 바뀌어도 항상 적용된다.
 * 모델, GPU가 바뀌면 budget-config만 수정한다.
 */
@Getter
@Component
public class BudgetGuard {

    private final int bMin;
    private final int bMax;

    public BudgetGuard(WorkerProperties properties) {
        this.bMin = properties.budget().bMin();
        this.bMax = properties.budget().bMax();
    }

    public int clamp(int candidate) {
        return Math.max(bMin, Math.min(bMax, candidate));
    }
}
