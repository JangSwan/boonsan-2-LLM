package com.llmserving.worker.credit;

import com.llmserving.common.LeaseResponse;
import com.llmserving.common.Request;
import com.llmserving.worker.adapter.LeasePort;
import com.llmserving.worker.adapter.UpstreamUnavailableException;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.scheduling.EngineEventListener;
import com.llmserving.worker.scheduling.EngineScheduler;
import com.llmserving.worker.scheduling.RequestState;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실제 여유로 크레딧을 계산해 그만큼만 pull (EA CreditManager, C2·T3).
 * <p>
 * 크레딧 n = min(남은 슬롯, ⌊남은 KV / 요청당 KV⌋). 단 미시작 Prefill 토큰이 상한 이상이면 0.
 * 밀린 일이 적은 워커가 먼저 인출하게 된다 (sd2 노트 "C2 결합").
 */
@Slf4j
@Component
public class CreditManager implements EngineEventListener {

    private final EngineScheduler engineScheduler;
    private final LeasePort leasePort;
    private final Clock clock;
    private final String workerId;
    private final double kvGbPerRequest;
    private final int maxUnstartedTokens;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService ackExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lease-ack");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean pulling;
    private volatile String versionTag;
    private volatile long lastNonEmptyPullAt;
    private volatile int lastUnstartedTokens;

    public CreditManager(EngineScheduler engineScheduler, LeasePort leasePort, Clock clock,
                         WorkerProperties properties) {
        this.engineScheduler = engineScheduler;
        this.leasePort = leasePort;
        this.clock = clock;
        this.workerId = properties.instanceId();
        this.kvGbPerRequest = properties.credit().kvGbPerRequest();
        this.maxUnstartedTokens = properties.credit().maxUnstartedTokens();
    }

    /** 크레딧 n 계산 (sd1 12번). */
    public int computeCredit() {
        Capacity capacity = engineScheduler.freeCapacity();
        if (capacity.unstartedTokens() >= maxUnstartedTokens) {
            return 0;
        }
        int byKv = kvGbPerRequest <= 0 ? capacity.freeSlots() : (int) Math.floor(capacity.freeKvGb() / kvGbPerRequest);
        return Math.max(0, Math.min(capacity.freeSlots(), byKv));
    }

    /** 주기 작업: 여유만큼 pull하고 받은 작업을 엔진에 투입한다 (sd1 10~19번). */
    @Scheduled(fixedDelayString = "${llm.worker.pull-interval-ms}")
    public void pullJobs() {
        inFlight.removeIf(id -> !engineScheduler.hasJob(id));  // 엔진 장애로 사라진 요청 정리
        if (!pulling || versionTag == null) {
            return;
        }
        int n = computeCredit();
        if (n <= 0) {
            return;
        }
        List<LeaseResponse> leases;
        try {
            leases = leasePort.pull(workerId, versionTag, n);
        } catch (UpstreamUnavailableException e) {
            log.debug("event=credit.pull-failed msg=broker 응답 없음, 다음 주기에 다시 시도");
            return;
        }
        if (leases.isEmpty()) {
            return;
        }
        lastNonEmptyPullAt = clock.millis();
        for (LeaseResponse lease : leases) {
            Request request = lease.request();
            inFlight.add(request.requestId());
            engineScheduler.submit(new RequestState(request.requestId(), request.versionTag(),
                    request.inputTokens(), request.prompt(), clock.millis()));
            try (var ignored = MDC.putCloseable("requestId", request.requestId())) {
                log.info("event=credit.job-submitted requestId={} leaseId={} credit={} inputTokens={} msg=작업 투입",
                        request.requestId(), lease.leaseId(), n, request.inputTokens());
            }
        }
    }

    /** 처리할 버전을 정하고 인출을 (다시) 시작한다 (sd6 15·21번). */
    public void setVersion(String versionTag) {
        this.versionTag = versionTag;
        if (!pulling) {
            pulling = true;
            log.info("event=worker.pull-started workerId={} versionTag={} msg=인출 시작", workerId, versionTag);
        }
    }

    /** 새 인출만 멈춘다. 진행 중 답변은 계속 생성한다 (sd3 3번, sd6 6번). 멱등. */
    public void stopPull() {
        if (pulling) {
            pulling = false;
            log.info("event=worker.pull-stopped workerId={} inFlight={} msg=새 인출 중지, 진행 중 답변은 계속 생성",
                    workerId, inFlight.size());
        }
    }

    @Override
    public void onFirstToken(String requestId, int unstartedTokens) {
        lastUnstartedTokens = unstartedTokens;
    }

    @Override
    public void onProgress(String requestId) {
        // 크레딧은 매 pull 시점의 실제 여유(freeCapacity)로 계산하므로 진척마다 따로 셀 것이 없다.
    }

    /** 완료 이벤트: 요청 ID로 lease를 찾아 complete ack를 보내고 크레딧을 반환한다 (sd1 24번). */
    @Override
    public void onCompleted(String requestId) {
        inFlight.remove(requestId);
        ackExecutor.submit(() -> {
            try (var ignored = MDC.putCloseable("requestId", requestId)) {
                leasePort.complete(requestId);
                log.info("event=credit.completed requestId={} msg=완료 ack", requestId);
            } catch (RuntimeException e) {
                log.warn("event=credit.complete-failed requestId={} msg=완료 ack 실패 (lease 만료로 정리됨): {}",
                        requestId, e.getMessage());
            }
        });
    }

    public boolean isPulling() {
        return pulling;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public long lastNonEmptyPullAt() {
        return lastNonEmptyPullAt;
    }

    /**
     * 공용 큐에 남은 내 버전 작업 수 (sd6 마지막 워커의 드레인 종료 판단). 크레딧이 0이어도 인출 없이 확인한다.
     * broker가 응답하지 않으면 UpstreamUnavailableException.
     */
    public int remainingForVersion() {
        return leasePort.remainingFor(workerId, versionTag);
    }

    public int lastUnstartedTokens() {
        return lastUnstartedTokens;
    }
}
