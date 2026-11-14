package com.llmserving.frontgateway;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.EnqueueResponse;
import com.llmserving.common.PositionResponse;
import com.llmserving.common.Request;
import com.llmserving.frontgateway.adapter.AdmissionPort;
import com.llmserving.frontgateway.adapter.UpstreamUnavailableException;
import com.llmserving.frontgateway.config.FrontGatewayProperties;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Queue Broker 접수 API 호출 (EA QueueBrokerClient, boundary). AdmissionPort를 구현한다.
 * 주 브로커가 응답하지 않거나 503(승격 전 복제본, 고립)이면 다른 주소로 바꿔 다시 보낸다 (sd5 12번 접수 재연결).
 * 같은 요청 ID를 다시 보내도 broker가 한 번만 적재하므로, 접수는 거절하지 않는다는 C2 원칙에 따라 정해진 시간 동안
 * 주소를 번갈아 가며 다시 보낸다 (동기 복제로 접수 지연이 늘거나 승격 중이어도 오류 대신 기다린다).
 * 낮은 epoch 응답은 옛 주 브로커의 것이므로 거부한다.
 */
@Slf4j
@Component
public class QueueBrokerClient implements AdmissionPort {

    private final List<String> urls;
    private final List<RestClient> brokers;
    private final long retryBudgetMs;
    private final long retryBackoffMs;
    private volatile int current;
    private volatile long maxEpoch;

    public QueueBrokerClient(RestClient.Builder builder, FrontGatewayProperties properties) {
        this.urls = List.copyOf(properties.brokerUrls());
        this.brokers = urls.stream().map(url -> builder.clone().baseUrl(url).build()).toList();
        this.retryBudgetMs = properties.brokerRetryBudgetMs();
        this.retryBackoffMs = properties.brokerRetryBackoffMs();
    }

    @Override
    public int enqueue(Request request) {
        EnqueueResponse response = call(client -> {
            EnqueueResponse r = client.post().uri(ApiPaths.Broker.REQUESTS)
                    .header(ApiPaths.HEADER_REQUEST_ID, request.requestId())
                    .body(request).retrieve().body(EnqueueResponse.class);
            checkEpoch(r == null ? 0 : r.epoch());
            return r;
        });
        return response == null ? 0 : response.position();
    }

    @Override
    public int getPosition(String requestId) {
        PositionResponse response = call(client -> {
            PositionResponse r = client.get().uri(ApiPaths.Broker.REQUEST_POSITION, requestId)
                    .header(ApiPaths.HEADER_REQUEST_ID, requestId)
                    .retrieve().body(PositionResponse.class);
            checkEpoch(r == null ? 0 : r.epoch());
            return r;
        });
        return response == null ? 0 : response.position();
    }

    private <T> T call(Function<RestClient, T> action) {
        long deadline = System.nanoTime() + retryBudgetMs * 1_000_000L;
        int attempt = 0;
        do {
            int index = current;
            try {
                return action.apply(brokers.get(index));
            } catch (HttpClientErrorException e) {
                throw e;
            } catch (StaleEpochResponse | RestClientException e) {
                switchFrom(index, e);
            }
            attempt++;
            if (attempt % brokers.size() == 0) {
                sleep(retryBackoffMs);
            }
        } while (System.nanoTime() < deadline);
        throw new UpstreamUnavailableException("broker가 응답하지 않습니다");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkEpoch(long epoch) {
        if (epoch < maxEpoch) {
            throw new StaleEpochResponse();
        }
        maxEpoch = epoch;
    }

    private synchronized void switchFrom(int index, Exception cause) {
        if (current == index && brokers.size() > 1) {
            current = (index + 1) % brokers.size();
            log.warn("event=broker.switched from={} to={} msg=broker 주소 전환: {}", urls.get(index),
                    urls.get(current), cause.getMessage());
        }
    }

    private static class StaleEpochResponse extends RuntimeException {
        StaleEpochResponse() {
            super("옛 주 브로커의 낮은 epoch 응답");
        }
    }
}
