package com.llmserving.fakeaws.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.InstanceReadyRequest;
import com.llmserving.common.ReclaimNoticeRequest;
import com.llmserving.fakeaws.config.FakeAwsProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** MembershipNoticePort의 HTTP 구현. 응답 없는 인스턴스는 건너뛴다. */
@Slf4j
@Component
public class MembershipNoticeRestAdapter implements MembershipNoticePort {

    private final List<RestClient> memberships;

    public MembershipNoticeRestAdapter(RestClient.Builder builder, FakeAwsProperties properties) {
        this.memberships = properties.membershipUrls().stream()
                .map(url -> builder.clone().baseUrl(url).build()).toList();
    }

    @Override
    public void reclaimNotice(String workerId) {
        send(ApiPaths.Membership.NOTICES, new ReclaimNoticeRequest(workerId));
    }

    @Override
    public void instanceReady(String instanceId) {
        send(ApiPaths.Membership.INSTANCES_READY, new InstanceReadyRequest(instanceId));
    }

    private void send(String path, Object body) {
        for (RestClient membership : memberships) {
            try {
                membership.post().uri(path).body(body).retrieve().toBodilessEntity();
            } catch (RestClientException e) {
                log.warn("event=aws.notice-failed path={} msg=membership 통지 실패: {}", path, e.getMessage());
            }
        }
    }
}
