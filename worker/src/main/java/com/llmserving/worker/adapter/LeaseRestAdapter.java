package com.llmserving.worker.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.LeaseResponse;
import com.llmserving.common.PullRequest;
import com.llmserving.common.PullResponse;
import com.llmserving.worker.config.WorkerProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LeasePort의 HTTP 구현. 브로커 승격 후 재연결을 여기서 처리한다 (CLAUDE.md 3절):
 * 현재 주소가 응답하지 않거나 503(승격 전 복제본, 고립)이면 다른 주소로 바꿔 한 번 더 시도한다.
 * 응답의 epoch가 지금까지 본 값보다 낮으면 옛 주 브로커의 응답이므로 거부한다 (fencing).
 */
@Slf4j
@Component
public class LeaseRestAdapter implements LeasePort {

    private final List<RestClient> brokers;
    private final List<String> urls;
    private final Map<String, String> leaseByRequest = new ConcurrentHashMap<>();
    private volatile int current;
    private volatile long maxEpoch;

    public LeaseRestAdapter(RestClient.Builder builder, WorkerProperties properties) {
        this.urls = List.copyOf(properties.brokerUrls());
        this.brokers = urls.stream().map(url -> builder.clone().baseUrl(url).build()).toList();
    }

    @Override
    public List<LeaseResponse> pull(String workerId, String versionTag, int n) {
        PullResponse response = call(client -> {
            PullResponse r = client.post().uri(ApiPaths.Broker.LEASES_PULL)
                    .body(new PullRequest(workerId, versionTag, n))
                    .retrieve().body(PullResponse.class);
            checkEpoch(r == null ? 0 : r.epoch());
            return r;
        });
        if (response == null || response.leases() == null) {
            return List.of();
        }
        response.leases().forEach(l -> leaseByRequest.put(l.requestId(), l.leaseId()));
        return response.leases();
    }

    @Override
    public void renew(String requestId) {
        String leaseId = leaseByRequest.get(requestId);
        if (leaseId == null) {
            throw new LeaseGoneException(requestId);
        }
        try {
            call(client -> {
                LeaseResponse r = client.post().uri(ApiPaths.Broker.LEASE_RENEW, leaseId)
                        .header(ApiPaths.HEADER_REQUEST_ID, requestId)
                        .retrieve().body(LeaseResponse.class);
                checkEpoch(r == null ? 0 : r.epoch());
                return r;
            });
        } catch (HttpClientErrorException.NotFound e) {
            leaseByRequest.remove(requestId);
            throw new LeaseGoneException(requestId);
        }
    }

    @Override
    public void complete(String requestId) {
        String leaseId = leaseByRequest.remove(requestId);
        if (leaseId == null) {
            return;
        }
        try {
            call(client -> client.post().uri(ApiPaths.Broker.LEASE_COMPLETE, leaseId)
                    .header(ApiPaths.HEADER_REQUEST_ID, requestId)
                    .retrieve().body(LeaseResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            log.info("event=lease.complete-ignored requestId={} msg=이미 없는 lease (만료·회수됨)", requestId);
        }
    }

    private <T> T call(Function<RestClient, T> action) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < brokers.size(); attempt++) {
            int index = current;
            try {
                return action.apply(brokers.get(index));
            } catch (HttpClientErrorException e) {
                throw e;  // 4xx는 그 주소의 판단이다 (404 lease 없음 등)
            } catch (StaleResponseException | RestClientException e) {
                // 연결 실패, 타임아웃, 5xx(503: 승격 전 복제본·고립), 옛 epoch → 다른 주소로
                last = e;
                switchFrom(index, e);
            }
        }
        throw new UpstreamUnavailableException("broker 응답 없음", last);
    }

    private void checkEpoch(long epoch) {
        if (epoch < maxEpoch) {
            throw new StaleResponseException(epoch, maxEpoch);
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

    /** 옛 주 브로커(낮은 epoch)의 응답. */
    static class StaleResponseException extends RuntimeException {
        StaleResponseException(long epoch, long maxEpoch) {
            super("낡은 epoch " + epoch + " (본 값 " + maxEpoch + ")");
        }
    }
}
