package com.llmserving.monitoring.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.QualitySample;
import com.llmserving.monitoring.config.MonitoringProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** QualitySamplePort의 HTTP 구현. */
@Component
public class QualitySampleRestAdapter implements QualitySamplePort {

    private final RestClient restClient;

    public QualitySampleRestAdapter(RestClient.Builder builder, MonitoringProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.modelManagerUrl()).build();
    }

    @Override
    public void send(QualitySample sample) {
        restClient.post().uri(ApiPaths.ModelManager.QUALITY_SAMPLES).body(sample).retrieve().toBodilessEntity();
    }
}
