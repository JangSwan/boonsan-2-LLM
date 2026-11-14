package com.llmserving.worker.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.WeightLoadRequest;
import com.llmserving.common.WeightLoadResponse;
import com.llmserving.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** WeightStorePort의 HTTP 구현. */
@Component
public class WeightStoreRestAdapter implements WeightStorePort {

    private final RestClient restClient;

    public WeightStoreRestAdapter(RestClient.Builder builder, WorkerProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.modelManagerUrl()).build();
    }

    @Override
    public WeightLoadResponse loadWeights(String workerId, String stageName) {
        return restClient.post()
                .uri(ApiPaths.ModelManager.WEIGHTS_LOAD)
                .body(new WeightLoadRequest(workerId, stageName))
                .retrieve()
                .body(WeightLoadResponse.class);
    }
}
