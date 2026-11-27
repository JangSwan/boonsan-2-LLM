package com.llmserving.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.QueueMetricsResponse;
import com.llmserving.common.WorkerMetricsResponse;
import com.llmserving.monitoring.config.MonitoringProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringTest {

    private final MonitoringProperties properties = new MonitoringProperties("monitoring", 2000, 3, 2,
            List.of(), List.of(), "", List.of());

    @Test
    void 알림은_최근_것부터_보관_개수만큼만_남긴다() {
        AlertService service = new AlertService(properties);
        service.raise(new AlertEvent("worker.failure", "1", 1));
        service.raise(new AlertEvent("recovery.service", "2", 2));
        service.raise(new AlertEvent("recovery.replenished", "3", 3));

        assertThat(service.getAlerts()).extracting(AlertEvent::message).containsExactly("3", "2");
    }

    @Test
    void 큐와_워커_지표를_이름별로_모으고_최신_값을_조회한다() {
        MetricsCollector collector = new MetricsCollector(
                () -> Optional.of(new QueueMetricsResponse(7, 1, 30, 32, 0, 7, 0)),
                () -> List.of(new WorkerMetricsResponse("worker-1", "v1", 6, 2, 3.0, 400, 512, 1800, 0.7, 0)),
                Clock.systemUTC(), properties);

        collector.collect();
        collector.collect();
        collector.collect();
        collector.collect();

        assertThat(collector.query("queue.length")).hasSize(3).allMatch(m -> m.value() == 7);
        assertThat(collector.query("worker.worker-1.ttft-p95-ms")).last().extracting(Metric::value).isEqualTo(1800.0);
        assertThat(collector.query(null)).extracting(Metric::name)
                .contains("queue.active-leases", "workers.responding", "worker.worker-1.sessions");
    }

    @Test
    void broker가_응답하지_않으면_그_주기를_건너뛴다() {
        MetricsCollector collector = new MetricsCollector(Optional::empty, List::of, Clock.systemUTC(), properties);
        collector.collect();
        assertThat(collector.query("queue.length")).isEmpty();
        assertThat(collector.query("workers.responding")).singleElement().extracting(Metric::value).isEqualTo(0.0);
    }
}
