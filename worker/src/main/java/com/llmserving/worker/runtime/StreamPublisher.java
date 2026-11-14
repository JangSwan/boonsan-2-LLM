package com.llmserving.worker.runtime;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.adapter.StreamPort;
import com.llmserving.worker.config.WorkerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 토큰을 Front Gateway로 비동기 푸시 (EA StreamPublisher, boundary). StreamPort를 구현한다.
 * 같은 요청의 조각은 같은 줄(단일 스레드)로 보내 순서를 지킨다. 실패하면 건너뛴다
 * (Front Gateway의 DuplicateFilter가 순번으로 거르므로 다음 조각은 그대로 이어진다).
 */
@Slf4j
@Component
public class StreamPublisher implements StreamPort {

    private final RestClient restClient;
    private final List<ExecutorService> lanes = new ArrayList<>();

    public StreamPublisher(RestClient.Builder builder, WorkerProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.frontGatewayUrl()).build();
        int count = Math.max(1, properties.streamLanes());
        for (int i = 0; i < count; i++) {
            int lane = i;
            lanes.add(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "stream-lane-" + lane);
                t.setDaemon(true);
                return t;
            }));
        }
    }

    @Override
    public void publish(StreamChunk chunk) {
        lanes.get(Math.floorMod(chunk.requestId().hashCode(), lanes.size())).submit(() -> send(chunk));
    }

    private void send(StreamChunk chunk) {
        try {
            restClient.post()
                    .uri(ApiPaths.FrontGateway.STREAM_CHUNKS, chunk.requestId())
                    .header(ApiPaths.HEADER_REQUEST_ID, chunk.requestId())
                    .body(chunk)
                    .retrieve()
                    .toBodilessEntity();
            if (chunk.isFirst()) {
                log.info("event=stream.first-token requestId={} msg=첫 토큰 푸시", chunk.requestId());
            }
        } catch (RestClientException e) {
            log.warn("event=stream.publish-failed requestId={} seq={} msg=토큰 푸시 실패: {}",
                    chunk.requestId(), chunk.seq(), e.getMessage());
        }
    }
}
