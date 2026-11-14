package com.llmserving.frontgateway;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.RatioResponse;
import com.llmserving.frontgateway.adapter.ModelConfigPort;
import com.llmserving.frontgateway.config.FrontGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Model Manager에서 v2 비율 조회 (EA ModelConfigClient, boundary). ModelConfigPort를 구현한다.
 * 요청마다 부르지 않고 주기적으로 조회해 둔 값을 쓴다. 응답이 없으면 마지막 값을 유지한다.
 */
@Slf4j
@Component
public class ModelConfigClient implements ModelConfigPort {

    private final RestClient restClient;
    private volatile RatioResponse current;

    public ModelConfigClient(RestClient.Builder builder, FrontGatewayProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.modelManagerUrl()).build();
        this.current = new RatioResponse(0, properties.defaultVersion(), null);
    }

    @Override
    public double getRatio() {
        return current.ratio();
    }

    @Override
    public RatioResponse currentRatio() {
        return current;
    }

    @Scheduled(fixedDelayString = "${llm.front-gateway.ratio-poll-interval-ms}")
    public void refresh() {
        try {
            RatioResponse response = restClient.get().uri(ApiPaths.ModelManager.RATIO).retrieve()
                    .body(RatioResponse.class);
            if (response != null && response.activeVersion() != null) {
                if (!response.equals(current)) {
                    log.info("event=version.ratio-changed ratio={} activeVersion={} targetVersion={} msg=버전 비율 갱신",
                            response.ratio(), response.activeVersion(), response.targetVersion());
                }
                current = response;
            }
        } catch (RestClientException e) {
            log.debug("event=version.ratio-poll-failed msg=model-manager 응답 없음, 마지막 비율 유지");
        }
    }
}
