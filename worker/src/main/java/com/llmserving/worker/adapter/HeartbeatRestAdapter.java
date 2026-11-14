package com.llmserving.worker.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.WorkerStatus;
import com.llmserving.worker.config.WorkerProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HeartbeatPort의 HTTP 구현. */
@Slf4j
@Component
public class HeartbeatRestAdapter implements HeartbeatPort {

    private final List<RestClient> memberships;

    public HeartbeatRestAdapter(RestClient.Builder builder, WorkerProperties properties) {
        this.memberships = properties.membershipUrls().stream()
                .map(url -> builder.clone().baseUrl(url).build()).toList();
    }

    @Override
    public void send(WorkerStatus status) {
        for (RestClient membership : memberships) {
            try {
                membership.post().uri(ApiPaths.Membership.HEARTBEATS).body(status).retrieve().toBodilessEntity();
            } catch (RestClientException e) {
                log.debug("event=heartbeat.failed msg=하트비트 전송 실패, 다음 주기에 다시 보냄: {}", e.getMessage());
            }
        }
    }
}
