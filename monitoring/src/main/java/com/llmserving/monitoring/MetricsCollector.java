package com.llmserving.monitoring;

import com.llmserving.common.QueueMetricsResponse;
import com.llmserving.common.WorkerMetricsResponse;
import com.llmserving.monitoring.adapter.QueueMetricsPort;
import com.llmserving.monitoring.adapter.WorkerMetricsPort;
import com.llmserving.monitoring.config.MonitoringProperties;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 큐 길이, 세션, GPU, TTFT 수집 (EA MetricsCollector). 지표 이름별로 최근 값을 보관한다.
 */
@Slf4j
@Component
public class MetricsCollector {

    private final QueueMetricsPort queueMetricsPort;
    private final WorkerMetricsPort workerMetricsPort;
    private final Clock clock;
    private final int historySize;
    private final Map<String, Deque<Metric>> history = new TreeMap<>();

    public MetricsCollector(QueueMetricsPort queueMetricsPort, WorkerMetricsPort workerMetricsPort, Clock clock,
                            MonitoringProperties properties) {
        this.queueMetricsPort = queueMetricsPort;
        this.workerMetricsPort = workerMetricsPort;
        this.clock = clock;
        this.historySize = properties.historySize();
    }

    @Scheduled(fixedDelayString = "${llm.monitoring.collect-interval-ms}")
    public void collect() {
        collectQueueLength();
        collectWorkerMetrics();
    }

    public void collectQueueLength() {
        Optional<QueueMetricsResponse> response = queueMetricsPort.fetch();
        response.ifPresent(r -> {
            record("queue.length", r.queueLength());
            record("queue.quarantined", r.quarantinedCount());
            record("queue.active-leases", r.activeLeases());
            record("queue.concurrency-cap", r.concurrencyCap());
            record("queue.length.short", r.shortLength());
            record("queue.length.long", r.longLength());
        });
    }

    public void collectWorkerMetrics() {
        List<WorkerMetricsResponse> workers = workerMetricsPort.fetchAll();
        record("workers.responding", workers.size());
        for (WorkerMetricsResponse w : workers) {
            String prefix = "worker." + w.workerId() + ".";
            record(prefix + "sessions", w.activeSessions());
            record(prefix + "free-slots", w.freeSlots());
            record(prefix + "unstarted-tokens", w.unstartedTokens());
            record(prefix + "step-budget", w.stepBudget());
            record(prefix + "ttft-p95-ms", w.ttftP95Ms());
            record(prefix + "gpu", w.gpuUtilization());
        }
    }

    /** 이름이 있으면 그 지표의 기록, 없으면 모든 지표의 최신 값. */
    public synchronized List<Metric> query(String name) {
        if (name != null && !name.isBlank()) {
            Deque<Metric> values = history.get(name);
            return values == null ? List.of() : new ArrayList<>(values);
        }
        return history.values().stream().map(Deque::peekLast).toList();
    }

    private synchronized void record(String name, double value) {
        Deque<Metric> values = history.computeIfAbsent(name, n -> new ArrayDeque<>());
        values.addLast(new Metric(name, value, clock.millis()));
        while (values.size() > historySize) {
            values.removeFirst();
        }
    }
}
