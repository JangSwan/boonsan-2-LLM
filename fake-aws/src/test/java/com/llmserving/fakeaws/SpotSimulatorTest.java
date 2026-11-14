package com.llmserving.fakeaws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.llmserving.fakeaws.adapter.MembershipNoticePort;
import com.llmserving.fakeaws.config.ApiException;
import com.llmserving.fakeaws.config.FakeAwsProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpotSimulatorTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final MembershipNoticePort membership = new MembershipNoticePort() {
        @Override
        public void reclaimNotice(String workerId) {
            events.add("notice:" + workerId);
        }

        @Override
        public void instanceReady(String instanceId) {
            events.add("ready:" + instanceId);
        }
    };
    private final SpotSimulator simulator = new SpotSimulator(membership, url -> events.add("boot:" + url),
            Clock.systemUTC(), new FakeAwsProperties("fake-aws", List.of("http://m1"),
            List.of("http://worker-spare:8096"), 0, 300));

    @Test
    void 시연_명령을_받으면_회수_통지를_보낸다() {
        simulator.reclaim("worker-5");
        assertThat(events).containsExactly("notice:worker-5");
    }

    @Test
    void 재생성_요청을_받으면_예비_워커에_기동_신호를_보낸_뒤_기동_완료를_알린다() {
        assertThat(simulator.requestInstance("replace-worker-5")).isEqualTo("worker-spare");

        await().atMost(Duration.ofSeconds(2)).until(() -> events.size() == 2);
        assertThat(events).containsExactly("boot:http://worker-spare:8096", "ready:worker-spare");
    }

    @Test
    void 같은_중복_방지_키의_두_번째_요청은_같은_인스턴스를_돌려주고_다시_기동하지_않는다() {
        simulator.requestInstance("replace-worker-5");
        assertThat(simulator.requestInstance("replace-worker-5")).isEqualTo("worker-spare");

        await().atMost(Duration.ofSeconds(2)).until(() -> events.size() == 2);
        assertThat(events).filteredOn(e -> e.startsWith("boot:")).hasSize(1);
    }

    @Test
    void 예비_인스턴스가_없으면_거부한다() {
        simulator.requestInstance("replace-worker-5");
        assertThatThrownBy(() -> simulator.requestInstance("replace-worker-4")).isInstanceOf(ApiException.class);
    }
}
