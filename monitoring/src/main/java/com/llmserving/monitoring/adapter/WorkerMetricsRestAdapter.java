package com.llmserving.monitoring.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.WorkerMetricsResponse;
import com.llmserving.monitoring.config.MonitoringProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** WorkerMetricsPort의 HTTP 구현. 응답 없는 워커(정지, 기동 전)는 건너뛴다. */
@Component
public class WorkerMetricsRestAdapter implements WorkerMetricsPort {

    private final List<RestClient> workers;

    public WorkerMetricsRestAdapter(RestClient.Builder builder, MonitoringProperties properties) {
        this.workers = properties.workerUrls().stream()
                .map(entry -> builder.clone().baseUrl(entry.substring(entry.indexOf('=') + 1).trim()).build()).toList();
    }

    @Override
    public List<WorkerMetricsResponse> fetchAll() {
        List<WorkerMetricsResponse> result = new ArrayList<>();
        for (RestClient worker : workers) {
            try {
                WorkerMetricsResponse r = worker.get().uri(ApiPaths.Worker.METRICS).retrieve()
                        .body(WorkerMetricsResponse.class);
                if (r != null && r.versionTag() != null) {
                    result.add(r);
                }
            } catch (RestClientException e) {
                // 응답 없는 워커는 건너뛴다
            }
        }
        return result;
    }
}
