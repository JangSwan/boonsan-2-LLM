package com.llmserving.worker.budget;

/**
 * 예산 정책 추상 클래스 (EA BudgetPolicy, T3).
 * 호출 순서는 런타임과 같다: EngineScheduler → budgetFor() → propose() → BudgetGuard.clamp().
 * budgetFor()는 고정 메서드(final)라 정책이 바뀌어도 Guard의 하한·상한이 항상 적용된다. 새 정책은 propose()만 재정의한다.
 */
public abstract class BudgetPolicy {

    private final BudgetGuard budgetGuard;

    protected BudgetPolicy(BudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
    }

    /** 스텝 예산 B (하한~상한). 재정의 금지. */
    public final int budgetFor(Backlog backlog) {
        return budgetGuard.clamp(propose(backlog));
    }

    /** B 후보. 정책 구현 클래스가 정한다. */
    public abstract int propose(Backlog backlog);
}
