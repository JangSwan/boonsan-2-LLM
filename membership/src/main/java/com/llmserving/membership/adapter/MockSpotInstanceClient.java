package com.llmserving.membership.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.InstanceRequest;
import com.llmserving.common.InstanceResponse;
import com.llmserving.membership.config.MembershipProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** mock 프로필의 SpotInstancePort: fake-aws를 부른다. real 구현과 같은 계약(중복 방지 키, 예외)을 지킨다. */
@Component
@Profile("mock")
public class MockSpotInstanceClient implements SpotInstancePort {

    private final RestClient restClient;

    public MockSpotInstanceClient(RestClient.Builder builder, MembershipProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.fakeAwsUrl()).build();
    }

    @Override
    public String requestInstance(String idempotencyKey) {
        InstanceResponse response = restClient.post().uri(ApiPaths.FakeAws.INSTANCES)
                .body(new InstanceRequest(idempotencyKey))
                .retrieve().body(InstanceResponse.class);
        if (response == null || response.instanceId() == null) {
            throw new IllegalStateException("fake-aws가 인스턴스를 배정하지 않았습니다");
        }
        return response.instanceId();
    }
}
