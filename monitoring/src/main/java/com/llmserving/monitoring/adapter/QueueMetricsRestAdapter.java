package com.llmserving.monitoring.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.QueueMetricsResponse;
import com.llmserving.monitoring.config.MonitoringProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * QueueMetricsPort의 HTTP 구현. 주 브로커와 복제본에 차례로 묻고, 동시 실행 수가 있는(실제로 lease를 다루는) 쪽 값을 쓴다.
 */
@Component
public class QueueMetricsRestAdapter implements QueueMetricsPort {

    private final List<RestClient> brokers;

    public QueueMetricsRestAdapter(RestClient.Builder builder, MonitoringProperties properties) {
        this.brokers = properties.brokerUrls().stream().map(url -> builder.clone().baseUrl(url).build()).toList();
    }

    @Override
    public Optional<QueueMetricsResponse> fetch() {
        QueueMetricsResponse best = null;
        for (RestClient broker : brokers) {
            try {
                QueueMetricsResponse r = broker.get().uri(ApiPaths.Broker.QUEUE_METRICS).retrieve()
                        .body(QueueMetricsResponse.class);
                if (r != null && (best == null || r.activeLeases() > best.activeLeases()
                        || r.queueLength() > best.queueLength())) {
                    best = r;
                }
            } catch (RestClientException e) {
                // 응답 없는 broker는 건너뛴다
            }
        }
        return Optional.ofNullable(best);
    }
}
