package com.llmserving.worker.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ModelConfigResponse;
import com.llmserving.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** ModelConfigPort의 HTTP 구현. */
@Component
public class ModelConfigRestAdapter implements ModelConfigPort {

    private final RestClient restClient;

    public ModelConfigRestAdapter(RestClient.Builder builder, WorkerProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.modelManagerUrl()).build();
    }

    @Override
    public ModelConfigResponse fetch(String workerId, String versionTag) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path(ApiPaths.ModelManager.CONFIG)
                            .queryParam("workerId", workerId)
                            .queryParamIfPresent("versionTag", java.util.Optional.ofNullable(versionTag))
                            .build())
                    .retrieve().body(ModelConfigResponse.class);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("model-manager 응답 없음", e);
        }
    }

    @Override
    public DrainStartResponse drainStart(String workerId) {
        try {
            return restClient.post().uri(ApiPaths.ModelManager.WORKER_DRAIN_START, workerId)
                    .retrieve().body(DrainStartResponse.class);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("model-manager 응답 없음", e);
        }
    }

    @Override
    public void ready(String workerId) {
        try {
            restClient.post().uri(ApiPaths.ModelManager.WORKER_READY, workerId).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("model-manager 응답 없음", e);
        }
    }
}
