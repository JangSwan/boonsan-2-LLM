package com.llmserving.membership.adapter;

import com.llmserving.common.AlertRequest;
import com.llmserving.common.ApiPaths;
import com.llmserving.membership.config.MembershipProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** AlertPort의 HTTP 구현. */
@Component
public class AlertRestAdapter implements AlertPort {

    private final RestClient restClient;

    public AlertRestAdapter(RestClient.Builder builder, MembershipProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.monitoringUrl()).build();
    }

    @Override
    public void raise(AlertRequest alert) {
        restClient.post().uri(ApiPaths.Monitoring.ALERTS).body(alert).retrieve().toBodilessEntity();
    }
}
