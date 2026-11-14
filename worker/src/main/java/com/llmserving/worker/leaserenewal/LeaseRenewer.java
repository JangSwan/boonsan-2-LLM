package com.llmserving.worker.leaserenewal;

import com.llmserving.worker.adapter.LeaseGoneException;
import com.llmserving.worker.adapter.LeasePort;
import com.llmserving.worker.adapter.UpstreamUnavailableException;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.scheduling.EngineEventListener;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 진척이 보고된 요청의 lease만 갱신 (EA LeaseRenewer, C2 단점 4 보완).
 * 정상적인 긴 생성은 계속 갱신되어 만료되지 않고, 엔진이 죽으면 진척 보고가 끊겨 갱신도 멈추므로
 * 만료는 실제 장애일 때만 일어난다 (sd4 5~9번).
 */
@Slf4j
@Component
public class LeaseRenewer implements EngineEventListener {

    private final LeasePort leasePort;
    private final Clock clock;
    private final long progressTimeoutMs;
    private final Map<String, Long> lastProgressAt = new ConcurrentHashMap<>();

    public LeaseRenewer(LeasePort leasePort, Clock clock, WorkerProperties properties) {
        this.leasePort = leasePort;
        this.clock = clock;
        this.progressTimeoutMs = properties.progressTimeoutMs();
    }

    @Override
    public void onFirstToken(String requestId, int unstartedTokens) {
        lastProgressAt.put(requestId, clock.millis());
    }

    @Override
    public void onProgress(String requestId) {
        lastProgressAt.put(requestId, clock.millis());
    }

    /** 완료된 요청은 더 갱신하지 않는다. */
    @Override
    public void onCompleted(String requestId) {
        lastProgressAt.remove(requestId);
    }

    /** 주기 작업: 최근 진척이 있는 요청만 갱신한다 (sd4 6번). 진척이 끊긴 요청은 갱신을 멈춘다 (sd4 9번). */
    @Scheduled(fixedDelayString = "${llm.worker.renew-interval-ms}")
    public void renewActive() {
        long now = clock.millis();
        for (Map.Entry<String, Long> entry : lastProgressAt.entrySet()) {
            String requestId = entry.getKey();
            if (now - entry.getValue() > progressTimeoutMs) {
                lastProgressAt.remove(requestId);
                log.warn("event=lease.renew-stopped requestId={} msg=진척 보고가 끊겨 lease 갱신 중단", requestId);
                continue;
            }
            try {
                leasePort.renew(requestId);
            } catch (LeaseGoneException e) {
                lastProgressAt.remove(requestId);
                log.info("event=lease.renew-gone requestId={} msg=broker에 lease가 없어 갱신 중단", requestId);
            } catch (UpstreamUnavailableException e) {
                log.debug("event=lease.renew-failed requestId={} msg=broker 응답 없음, 다음 주기에 다시 시도", requestId);
            }
        }
    }

    public boolean isTracking(String requestId) {
        return lastProgressAt.containsKey(requestId);
    }
}
