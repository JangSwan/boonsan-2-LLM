package com.llmserving.broker.adapter;

import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.retry.FailureNotifier;
import com.llmserving.common.ApiPaths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * FailureNotifier(EA, broker.retry)의 HTTP 구현. front-gateway StreamController → StreamRelay.notifyFailure.
 * lease 처리 잠금을 오래 잡지 않도록 별도 스레드에서 보내고, 응답이 없으면 정해진 횟수만큼 다시 보낸다.
 */
@Slf4j
@Component
public class FailureNotifierRestAdapter implements FailureNotifier {

    private final RestClient restClient;
    private final int attempts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "failure-notifier");
        t.setDaemon(true);
        return t;
    });

    public FailureNotifierRestAdapter(RestClient.Builder builder, BrokerProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.frontGatewayUrl()).build();
        this.attempts = Math.max(1, properties.failureNotifyAttempts());
    }

    @Override
    public void notifyFailure(String requestId) {
        executor.submit(() -> send(requestId));
    }

    private void send(String requestId) {
        for (int i = 1; i <= attempts; i++) {
            try {
                restClient.post()
                        .uri(ApiPaths.FrontGateway.FAILURES, requestId)
                        .header(ApiPaths.HEADER_REQUEST_ID, requestId)
                        .retrieve()
                        .toBodilessEntity();
                log.info("event=failure.notified requestId={} msg=Front Gateway에 실패 통지", requestId);
                return;
            } catch (RestClientException e) {
                log.warn("event=failure.notify-failed requestId={} attempt={} msg=실패 통지 실패: {}",
                        requestId, i, e.getMessage());
                sleepQuietly(200L * i);
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
