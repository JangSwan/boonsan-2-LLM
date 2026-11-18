package com.llmserving.worker.agent;

import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ModelConfigResponse;
import com.llmserving.common.StageResponse;
import com.llmserving.common.WorkerStatus;
import com.llmserving.worker.adapter.HeartbeatPort;
import com.llmserving.worker.adapter.ModelConfigPort;
import com.llmserving.worker.adapter.UpstreamUnavailableException;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.credit.CreditManager;
import com.llmserving.worker.runtime.ModelRuntime;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하트비트, 인출 중지, 설정 감지, 재적재 지시, 부팅 시 자체 적재와 생성 시험 (EA WorkerAgent, R3).
 * <ul>
 *   <li>부팅: 활성 단계 가중치 적재 → 생성 시험 → 통과하면 활성 버전 인출 시작, 상태 보고 (sd3 18~19번).</li>
 *   <li>교체: 목표 버전이 내 버전과 다르면 드레인 허가를 받고(한 대씩) 인출 중지 → 드레인 → 재적재 →
 *       생성 시험 → 준비 보고 → 새 버전 인출 (sd6 5~11번). 롤백도 같은 절차다.</li>
 *   <li>현재 버전을 인출하는 마지막 워커라면 남은 작업을 마저 가져간 뒤 멈춘다 (큐에 옛 버전 작업이 남지 않게).</li>
 * </ul>
 * worker-spare는 기동 신호(boot)를 받기 전에는 하트비트도 pull도 하지 않는다.
 */
@Slf4j
@Component
public class WorkerAgent {

    public static final String STATE_WAITING_BOOT = "WAITING_BOOT";
    public static final String STATE_BOOTING = "BOOTING";
    public static final String STATE_PULLING = "PULLING";
    public static final String STATE_DRAINING = "DRAINING";
    public static final String STATE_RELOADING = "RELOADING";
    public static final String STATE_STOPPED = "STOPPED";

    private final WorkerProperties properties;
    private final CreditManager creditManager;
    private final ModelRuntime modelRuntime;
    private final HeartbeatPort heartbeatPort;
    private final ModelConfigPort modelConfigPort;
    private final Clock clock;
    private final String workerId;

    private volatile boolean booted;
    private volatile String state = STATE_WAITING_BOOT;
    private volatile boolean selfTestPassed;
    private volatile String versionTag;
    private volatile boolean stoppedByMembership;
    private volatile boolean draining;
    private volatile boolean lastOfVersion;
    private volatile long drainStartedAt;
    private volatile long noOldWorkSince;

    public WorkerAgent(WorkerProperties properties, CreditManager creditManager, ModelRuntime modelRuntime,
                       HeartbeatPort heartbeatPort, ModelConfigPort modelConfigPort, Clock clock) {
        this.properties = properties;
        this.creditManager = creditManager;
        this.modelRuntime = modelRuntime;
        this.heartbeatPort = heartbeatPort;
        this.modelConfigPort = modelConfigPort;
        this.clock = clock;
        this.workerId = properties.instanceId();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.startOnBootSignal()) {
            log.info("event=worker.waiting-boot workerId={} msg=기동 신호 대기 (예비 인스턴스)", workerId);
            return;
        }
        boot();
    }

    /** 인스턴스 기동. 이후 설정 감지 주기에서 가중치 적재와 생성 시험을 한다. 멱등. */
    public synchronized boolean boot() {
        if (booted) {
            return false;
        }
        booted = true;
        state = STATE_BOOTING;
        log.info("event=worker.booted workerId={} msg=인스턴스 기동", workerId);
        return true;
    }

    /** 주기 작업: 하트비트 · 상태 보고 (sd3 19번). */
    @Scheduled(fixedDelayString = "${llm.worker.heartbeat-interval-ms}")
    public void sendHeartbeat() {
        if (!booted) {
            return;
        }
        heartbeatPort.send(new WorkerStatus(workerId, versionTag, state, creditManager.lastUnstartedTokens(),
                selfTestPassed));
    }

    /** 인출 중지 (sd3 2~3번). 회수 예정 인스턴스이므로 이후 교체 절차도 하지 않는다. 멱등. */
    public void stopPull() {
        stoppedByMembership = true;
        creditManager.stopPull();
        state = STATE_STOPPED;
    }

    /** 주기 작업: 설정 변경 감지 (sd6 5·28번). */
    @Scheduled(fixedDelayString = "${llm.worker.config-poll-interval-ms}")
    public synchronized void onConfigChanged() {
        if (!booted || stoppedByMembership) {
            return;
        }
        ModelConfigResponse config;
        try {
            config = modelConfigPort.fetch(workerId, versionTag);
        } catch (UpstreamUnavailableException e) {
            log.debug("event=worker.config-poll-failed msg=model-manager 응답 없음, 다음 주기에 다시 시도");
            return;
        }
        if (versionTag == null) {
            joinWith(config.activeVersion(), config.activeStage());
            return;
        }
        boolean swapping = config.targetVersion() != null;
        String desiredVersion = swapping ? config.targetVersion() : config.activeVersion();
        StageResponse desiredStage = swapping ? config.targetStage() : config.activeStage();
        if (desiredVersion == null || desiredStage == null || (desiredVersion.equals(versionTag) && !draining)) {
            return;
        }
        try {
            swapTo(desiredVersion, desiredStage);
        } catch (UpstreamUnavailableException e) {
            log.debug("event=worker.swap-paused msg=model-manager 응답 없음, 다음 주기에 이어서 진행");
        }
    }

    /** 재적재 지시 (sd6 8번): 가중치를 올린다. */
    public void reload(StageResponse stage) {
        state = STATE_RELOADING;
        selfTestPassed = false;
        modelRuntime.loadWeights(stage);
        log.info("event=model.reloaded workerId={} stage={} msg=가중치 재적재", workerId, stage.name());
    }

    /** 생성 시험 (sd3 18번, sd6 10번). */
    public boolean selfTest() {
        selfTestPassed = modelRuntime.generationTest();
        log.info("event=worker.self-test workerId={} passed={} msg=생성 시험", workerId, selfTestPassed);
        return selfTestPassed;
    }

    public boolean isPulling() {
        return creditManager.isPulling();
    }

    public String getState() {
        return state;
    }

    public String getVersionTag() {
        return versionTag;
    }

    /** 부팅 후 첫 적재: 활성 단계로 적재하고 시험을 통과하면 합류한다. */
    private void joinWith(String version, StageResponse stage) {
        try {
            reload(stage);
        } catch (RuntimeException e) {
            state = STATE_BOOTING;
            log.warn("event=worker.load-failed workerId={} msg=가중치 적재 실패, 다음 주기에 다시 시도: {}",
                    workerId, e.getMessage());
            return;
        }
        if (!selfTest()) {
            state = STATE_BOOTING;
            return;
        }
        versionTag = version;
        creditManager.setVersion(version);
        state = STATE_PULLING;
        sendHeartbeat();
        log.info("event=worker.ready workerId={} versionTag={} stage={} msg=적재·시험 통과, 인출 시작",
                workerId, version, stage.name());
    }

    /** 교체·롤백 롤링 절차의 한 주기. 조건이 안 되면 다음 주기에 이어서 한다. */
    private void swapTo(String desiredVersion, StageResponse desiredStage) {
        if (!draining) {
            DrainStartResponse grant = modelConfigPort.drainStart(workerId);
            if (grant == null || !grant.granted()) {
                return;  // 다른 워커가 교체 중: 한 대씩
            }
            draining = true;
            lastOfVersion = grant.lastOfVersion();
            drainStartedAt = clock.millis();
            noOldWorkSince = 0;
            state = STATE_DRAINING;
            if (!lastOfVersion) {
                creditManager.stopPull();
            }
            log.info("event=model.drain-started workerId={} from={} to={} lastOfVersion={} msg=드레인 시작",
                    workerId, versionTag, desiredVersion, lastOfVersion);
        }
        if (creditManager.isPulling()) {
            if (!oldVersionWorkDone()) {
                return;  // 옛 버전 작업을 마저 가져가는 중
            }
            creditManager.stopPull();
        }
        if (creditManager.inFlightCount() > 0) {
            return;  // 진행 중 답변 완료까지 드레인
        }
        try {
            reload(desiredStage);
        } catch (RuntimeException e) {
            log.warn("event=model.reload-failed workerId={} msg=재적재 실패, 다음 주기에 다시 시도: {}",
                    workerId, e.getMessage());
            return;
        }
        if (!selfTest()) {
            return;
        }
        modelConfigPort.ready(workerId);
        versionTag = desiredVersion;
        draining = false;
        creditManager.setVersion(desiredVersion);
        state = STATE_PULLING;
        log.info("event=model.worker-swapped workerId={} versionTag={} stage={} msg=준비 완료 보고, 새 버전 인출",
                workerId, desiredVersion, desiredStage.name());
    }

    /**
     * 현재 버전의 마지막 워커가 인출을 멈춰도 되는지 (sd6, 큐에 옛 버전 작업이 남지 않게).
     * "한동안 빈 pull"만으로는 판단하지 않는다: 피크에는 슬롯이 차 크레딧이 0이거나 4대분 상한에 걸려
     * 빈 pull이 이어져도 큐에 옛 버전 작업이 남아 있을 수 있다. 그래서 broker에 남은 같은 버전 작업 수를 묻고,
     * 남은 작업과 진행 중 답변이 모두 0인 상태가 조용한 시간(last-drain-quiet-ms) 동안 이어질 때만 멈춘다.
     * 조용한 시간은 Front Gateway가 비율을 다시 읽기 전에 옛 버전 태그로 막 들어온 요청을 받기 위한 여유다.
     */
    private boolean oldVersionWorkDone() {
        int remaining = creditManager.remainingForVersion();
        long now = clock.millis();
        if (remaining > 0 || creditManager.inFlightCount() > 0) {
            noOldWorkSince = 0;
            log.debug("event=model.drain-waiting workerId={} remaining={} inFlight={} msg=옛 버전 작업 처리 중",
                    workerId, remaining, creditManager.inFlightCount());
            return false;
        }
        if (noOldWorkSince == 0) {
            noOldWorkSince = now;
        }
        long quietSince = Math.max(noOldWorkSince, creditManager.lastNonEmptyPullAt());
        return now - quietSince >= properties.lastDrainQuietMs();
    }
}
