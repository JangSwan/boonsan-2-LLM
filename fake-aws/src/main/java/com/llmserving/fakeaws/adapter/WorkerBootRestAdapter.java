package com.llmserving.fakeaws.adapter;

import com.llmserving.common.ApiPaths;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** WorkerBootPort의 HTTP 구현. */
@Component
public class WorkerBootRestAdapter implements WorkerBootPort {

    private final RestClient.Builder builder;

    public WorkerBootRestAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public void boot(String instanceUrl) {
        builder.clone().baseUrl(instanceUrl).build()
                .post().uri(ApiPaths.Worker.BOOT).retrieve().toBodilessEntity();
    }
}
