package com.llmserving.worker.budget;

import com.llmserving.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

/**
 * 밀린 Prefill이 많으면 B를 키우는 기본 정책 (EA 런타임 박스 Budget Controller). 이름은 Controller지만 입구가 아닌 로직 클래스다.
 * <p>
 * B 후보 = Decode 수 + ⌈미시작 Prefill × 스텝 목표 시간 / 목표 첫 토큰 시간⌉.
 * 즉 지금 밀린 Prefill을 목표 첫 토큰 시간 안의 스텝들에 나눠 읽도록 스텝당 Prefill 양을 정한다.
 */
@Component
public class BudgetController extends BudgetPolicy {

    private final int targetTtftMs;
    private final int stepTargetMs;

    public BudgetController(BudgetGuard budgetGuard, WorkerProperties properties) {
        super(budgetGuard);
        this.targetTtftMs = properties.budget().targetTtftMs();
        this.stepTargetMs = properties.budget().stepTargetMs();
    }

    @Override
    public int propose(Backlog backlog) {
        long prefillPerStep = ((long) backlog.unstartedPrefill() * stepTargetMs + targetTtftMs - 1) / targetTtftMs;
        long candidate = backlog.decodeCount() + prefillPerStep;
        return (int) Math.min(Integer.MAX_VALUE, candidate);
    }
}
