package com.llmserving.broker.adapter;

import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.replication.StaleEpochException;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.ReplicationRequest;
import com.llmserving.common.ReplicationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ReplicaPort의 HTTP 구현. 주 브로커가 복제본 broker 인스턴스를 호출한다.
 */
@Component
public class ReplicaRestAdapter implements ReplicaPort {

    private final RestClient restClient;
    private final boolean enabled;

    public ReplicaRestAdapter(RestClient.Builder builder, BrokerProperties properties) {
        this.enabled = properties.hasReplica();
        this.restClient = enabled ? builder.clone().baseUrl(properties.replicaUrl()).build() : null;
    }

    @Override
    public ReplicationResponse send(ReplicationRequest request) {
        if (!enabled) {
            throw new ReplicaUnavailableException("복제본 주소가 설정되지 않았습니다", null);
        }
        try {
            ReplicationResponse response = restClient.post()
                    .uri(ApiPaths.Broker.REPLICATIONS)
                    .body(request)
                    .retrieve()
                    .body(ReplicationResponse.class);
            if (response == null) {
                throw new ReplicaUnavailableException("복제본 응답 본문이 없습니다", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                // 복제본이 더 높은 epoch를 가짐: 이미 다른 주 브로커가 승격했다 (fencing).
                throw new StaleEpochException(request.epoch(), request.epoch() + 1);
            }
            throw new ReplicaUnavailableException("복제본이 거부했습니다: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new ReplicaUnavailableException("복제본 호출 실패: " + e.getMessage(), e);
        }
    }
}
