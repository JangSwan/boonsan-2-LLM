package com.llmserving.worker.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.worker.WorkerTestSupport;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class BudgetPolicyTest {

    private final BudgetGuard guard = new BudgetGuard(WorkerTestSupport.properties()); // 256 ~ 2048
    private final BudgetController controller = new BudgetController(guard, WorkerTestSupport.properties());

    @Test
    void Guard는_B를_하한과_상한_사이로_자른다() {
        assertThat(guard.clamp(10)).isEqualTo(256);
        assertThat(guard.clamp(1000)).isEqualTo(1000);
        assertThat(guard.clamp(99_999)).isEqualTo(2048);
    }

    @Test
    void 밀린_Prefill이_많으면_B를_키운다() {
        // 4000토큰을 3000ms 안의 100ms 스텝들에 나눠 읽으면 스텝당 134 + Decode 2
        assertThat(controller.propose(new Backlog(4000, 2))).isEqualTo(136);
        assertThat(controller.propose(new Backlog(40_000, 8))).isEqualTo(1342);
        assertThat(controller.propose(new Backlog(0, 3))).isEqualTo(3);
    }

    @Test
    void budgetFor는_정책_결과에_항상_Guard를_적용한다() {
        assertThat(controller.budgetFor(new Backlog(0, 3))).isEqualTo(256);
        assertThat(controller.budgetFor(new Backlog(40_000, 8))).isEqualTo(1342);
        assertThat(controller.budgetFor(new Backlog(1_000_000, 0))).isEqualTo(2048);
    }

    @Test
    void 정책을_바꿔도_Guard가_적용된다() {
        BudgetPolicy greedy = new BudgetPolicy(guard) {
            @Override
            public int propose(Backlog backlog) {
                return Integer.MAX_VALUE;
            }
        };
        assertThat(greedy.budgetFor(new Backlog(0, 0))).isEqualTo(2048);
    }

    @Test
    void budgetFor는_재정의할_수_없는_고정_메서드다() throws Exception {
        assertThat(Modifier.isFinal(BudgetPolicy.class.getMethod("budgetFor", Backlog.class).getModifiers())).isTrue();
        assertThat(Modifier.isAbstract(BudgetPolicy.class.getModifiers())).isTrue();
    }
}
