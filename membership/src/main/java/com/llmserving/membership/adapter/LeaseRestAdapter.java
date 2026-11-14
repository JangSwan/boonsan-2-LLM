package com.llmserving.membership.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.ReclaimResponse;
import com.llmserving.membership.config.MembershipProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** LeasePort의 HTTP 구현. 주 브로커가 응답하지 않거나 503이면 다른 주소로 전환한다. 낮은 epoch 응답은 거부한다. */
@Slf4j
@Component
public class LeaseRestAdapter implements LeasePort {

    private final List<String> urls;
    private final List<RestClient> brokers;
    private volatile int current;
    private volatile long maxEpoch;

    public LeaseRestAdapter(RestClient.Builder builder, MembershipProperties properties) {
        this.urls = List.copyOf(properties.brokerUrls());
        this.brokers = urls.stream().map(url -> builder.clone().baseUrl(url).build()).toList();
    }

    @Override
    public int reclaim(String workerId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < brokers.size(); attempt++) {
            int index = current;
            try {
                ReclaimResponse response = brokers.get(index).post()
                        .uri(ApiPaths.Broker.WORKER_RECLAIM, workerId)
                        .retrieve().body(ReclaimResponse.class);
                if (response == null || response.epoch() < maxEpoch) {
                    throw new IllegalStateException("옛 주 브로커의 낮은 epoch 응답");
                }
                maxEpoch = response.epoch();
                return response.reclaimedCount();
            } catch (HttpClientErrorException e) {
                throw e;
            } catch (RestClientException | IllegalStateException e) {
                last = e;
                switchFrom(index, e);
            }
        }
        throw new IllegalStateException("broker가 응답하지 않습니다", last);
    }

    private synchronized void switchFrom(int index, Exception cause) {
        if (current == index && brokers.size() > 1) {
            current = (index + 1) % brokers.size();
            log.warn("event=broker.switched from={} to={} msg=broker 주소 전환: {}", urls.get(index),
                    urls.get(current), cause.getMessage());
        }
    }
}
