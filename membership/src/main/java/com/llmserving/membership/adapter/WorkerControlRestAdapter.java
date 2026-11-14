package com.llmserving.membership.adapter;

import com.llmserving.common.ApiPaths;
import com.llmserving.membership.config.MembershipProperties;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** WorkerControlPort의 HTTP 구현. 워커 주소의 호스트 이름(또는 "ID=주소"의 ID)이 워커 ID다. 응답이 없으면 기록만 한다. */
@Slf4j
@Component
public class WorkerControlRestAdapter implements WorkerControlPort {

    private final Map<String, RestClient> workers;

    public WorkerControlRestAdapter(RestClient.Builder builder, MembershipProperties properties) {
        this.workers = properties.workerUrls().stream().collect(Collectors.toMap(
                WorkerControlRestAdapter::workerId, entry -> builder.clone().baseUrl(url(entry)).build(),
                (a, b) -> a));
    }

    /** "worker-1=http://localhost:8091" 형식이면 앞부분, 아니면 주소의 호스트 이름이 워커 ID다. */
    static String workerId(String entry) {
        int eq = entry.indexOf('=');
        return eq > 0 ? entry.substring(0, eq).trim() : URI.create(entry.trim()).getHost();
    }

    static String url(String entry) {
        int eq = entry.indexOf('=');
        return (eq > 0 ? entry.substring(eq + 1) : entry).trim();
    }

    @Override
    public void stopPull(String workerId) {
        RestClient client = workers.get(workerId);
        if (client == null) {
            log.warn("event=worker.unknown workerId={} msg=주소를 모르는 워커", workerId);
            return;
        }
        try {
            client.post().uri(ApiPaths.Worker.STOP_PULL).retrieve().toBodilessEntity();
            log.info("event=worker.pull-stopped workerId={} msg=인출 중지 지시", workerId);
        } catch (RestClientException e) {
            log.warn("event=worker.stop-pull-failed workerId={} msg=인출 중지 지시 실패: {}", workerId, e.getMessage());
        }
    }
}
