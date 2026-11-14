package com.llmserving.modelmanager;

import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ErrorCode;
import com.llmserving.common.ModelConfigResponse;
import com.llmserving.common.RatioResponse;
import com.llmserving.common.StageResponse;
import com.llmserving.common.SwapStatusResponse;
import com.llmserving.modelmanager.config.ApiException;
import com.llmserving.modelmanager.config.ModelConfigStore;
import com.llmserving.modelmanager.config.ModelManagerProperties;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 활성 모델, 단계, v2 비율의 단일 기준점 (EA ModelConfig, U1). 교체와 롤백은 model-config 한 곳만 바꾼다.
 * <ul>
 *   <li>setTarget: QualityGate를 통과한 단계만 목표로 기록한다 (sd6 3~4번).</li>
 *   <li>워커는 주기적으로 설정을 조회하고, 목표 버전이 다르면 드레인 허가(한 번에 한 대) → 재적재 → 준비 보고를 한다.</li>
 *   <li>v2 비율 = 목표 버전으로 준비된 워커 / 인출 중인 워커. 모두 목표 버전이면 switchTo로 확정한다 (sd6 22번).</li>
 *   <li>롤백도 switchTo(U0)로 같은 롤링 절차를 쓴다 (sd6 27~30번).</li>
 * </ul>
 * Model Manager는 다른 서브시스템을 호출하지 않는다: 워커 상태는 워커의 설정 조회로만 안다.
 */
@Slf4j
@Component
public class ModelConfig {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_SWAPPING = "SWAPPING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ROLLING_BACK = "ROLLING_BACK";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    private final QualityGate qualityGate;
    private final ModelConfigStore store;
    private final Clock clock;
    private final long workerSeenTimeoutMs;
    private final long drainTimeoutMs;
    private final Map<String, WorkerView> workers = new HashMap<>();

    private ModelVersion active;
    private ModelVersion target;
    private String status;
    private long updatedAt;
    private int versionCounter;
    private String drainingWorker;
    private long drainStartedAt;

    public ModelConfig(QualityGate qualityGate, ModelConfigStore store, Clock clock,
                       ModelManagerProperties properties) {
        this.qualityGate = qualityGate;
        this.store = store;
        this.clock = clock;
        this.workerSeenTimeoutMs = properties.workerSeenTimeoutMs();
        this.drainTimeoutMs = properties.drainTimeoutMs();
        this.updatedAt = clock.millis();
        ModelConfigStore.Snapshot snapshot = store.load().orElseGet(() -> new ModelConfigStore.Snapshot(
                properties.activeModel(), properties.activeVersion(), properties.activeStage(), null, null,
                parseVersion(properties.activeVersion()), STATUS_IDLE));
        this.active = new ModelVersion(snapshot.activeModel(), snapshot.activeVersion(),
                qualityGate.findStage(snapshot.activeStage()));
        this.target = snapshot.targetStage() == null ? null : new ModelVersion(snapshot.activeModel(),
                snapshot.targetVersion(), qualityGate.findStage(snapshot.targetStage()));
        this.versionCounter = Math.max(snapshot.versionCounter(), parseVersion(snapshot.activeVersion()));
        this.status = snapshot.status();
        log.info("event=model.config-loaded activeVersion={} activeStage={} targetVersion={} msg=model-config 적재",
                active.version(), active.stage().getName(), target == null ? null : target.version());
        if (target == null && properties.targetStage() != null && !properties.targetStage().isBlank()) {
            setTarget(new ModelVersion(active.modelName(), nextVersionTag(),
                    qualityGate.findStage(properties.targetStage())));
        }
    }

    /** v2 비율 (sd6 12·13번). */
    public synchronized double getRatio() {
        return versionRatio().ratio();
    }

    /** 목표 단계 기록 (sd6 4번). QualityGate를 통과한 단계만 허용한다. 교체 중이면 거부한다. */
    public synchronized void setTarget(ModelVersion version) {
        if (target != null) {
            throw new ApiException(ErrorCode.ALREADY_PROCESSED, "이미 교체가 진행 중입니다 (목표 " + target.version() + ")");
        }
        GateResult gate = qualityGate.evaluate(version.stage());
        if (!gate.passed()) {
            throw new ApiException(ErrorCode.QUALITY_GATE_FAILED, version.stage().getName()
                    + " 단계는 품질 게이트를 통과하지 못했습니다 (점수 " + gate.qualityScore() + ")");
        }
        target = version;
        changeStatus(STATUS_SWAPPING);
        log.info("event=model.swap-started targetVersion={} stage={} qualityScore={} msg=목표 단계 기록, 교체 시작",
                version.version(), version.stage().getName(), gate.qualityScore());
    }

    /** 드레인 시작 보고 (sd6 7·17번). 한 번에 한 대만 허가한다. */
    public synchronized DrainStartResponse onDrainStart(String workerId) {
        long now = clock.millis();
        WorkerView me = workers.get(workerId);
        String desired = desiredVersion();
        if (me != null && desired.equals(me.versionTag())) {
            return new DrainStartResponse(workerId, false, false);
        }
        if (drainingWorker != null && !drainingWorker.equals(workerId) && now - drainStartedAt < drainTimeoutMs
                && isLive(workers.get(drainingWorker), now)) {
            return new DrainStartResponse(workerId, false, false);
        }
        drainingWorker = workerId;
        drainStartedAt = now;
        String myVersion = me == null ? null : me.versionTag();
        boolean lastOfVersion = liveWorkers(now).entrySet().stream()
                .noneMatch(e -> !e.getKey().equals(workerId) && myVersion != null
                        && myVersion.equals(e.getValue().versionTag()));
        log.info("event=model.drain-granted workerId={} from={} to={} lastOfVersion={} msg=드레인 허가",
                workerId, myVersion, desired, lastOfVersion);
        return new DrainStartResponse(workerId, true, lastOfVersion);
    }

    /** 준비 완료 보고 (sd6 11·20번). 비율을 갱신하고, 모두 목표 버전이면 확정한다. */
    public synchronized void onReady(String workerId) {
        if (workerId.equals(drainingWorker)) {
            drainingWorker = null;
        }
        workers.put(workerId, new WorkerView(desiredVersion(), clock.millis()));
        log.info("event=model.worker-ready workerId={} versionTag={} ratio={} msg=준비 완료 보고, v2 비율 갱신",
                workerId, desiredVersion(), versionRatio().ratio());
        checkCompletion();
    }

    /**
     * 단계 전환 (sd6 22·27번).
     * <ul>
     *   <li>목표 단계로: 모든 워커가 준비됐으므로 활성으로 확정한다.</li>
     *   <li>활성 단계로 (교체 도중 롤백): 목표를 취소하고, 목표 버전으로 바뀐 워커가 활성 버전으로 되돌아가게 한다.</li>
     *   <li>그 밖의 단계로 (교체 완료 후 롤백): 그 단계를 새 버전으로 목표에 둔다 (게이트 없이, 같은 롤링 절차).</li>
     * </ul>
     */
    public synchronized void switchTo(LadderStage stage) {
        if (target != null && target.stage().getName().equals(stage.getName())) {
            active = target;
            target = null;
            changeStatus(STATUS_ROLLING_BACK.equals(status) ? STATUS_ROLLED_BACK : STATUS_COMPLETED);
            log.info("event=model.switched activeVersion={} stage={} msg=v2 비율 100%, 활성 단계 확정",
                    active.version(), stage.getName());
        } else if (active.stage().getName().equals(stage.getName())) {
            target = null;
            changeStatus(STATUS_ROLLING_BACK);
            log.warn("event=model.swap-cancelled activeVersion={} msg=교체 취소, 활성 버전으로 되돌림", active.version());
            checkCompletion();
        } else {
            target = new ModelVersion(active.modelName(), nextVersionTag(), stage);
            changeStatus(STATUS_ROLLING_BACK);
            log.warn("event=model.rollback-started targetVersion={} stage={} msg=롤백 목표 기록",
                    target.version(), stage.getName());
        }
    }

    public synchronized LadderStage currentStage() {
        return active.stage();
    }

    /** 워커의 설정 조회 (sd6 5·28번). 조회한 워커와 그 버전을 기억해 비율을 센다. */
    public synchronized ModelConfigResponse configFor(String workerId, String versionTag) {
        if (workerId != null) {
            String version = versionTag != null ? versionTag
                    : workers.getOrDefault(workerId, new WorkerView(null, 0)).versionTag();
            workers.put(workerId, new WorkerView(version, clock.millis()));
            checkCompletion();
        }
        return new ModelConfigResponse(active.modelName(), active.version(), toResponse(active.stage()),
                target == null ? null : target.version(), target == null ? null : toResponse(target.stage()),
                versionRatio().ratio());
    }

    public synchronized RatioResponse ratioResponse() {
        return new RatioResponse(versionRatio().ratio(), active.version(), target == null ? null : target.version());
    }

    public synchronized SwapStatusResponse statusView() {
        return new SwapStatusResponse(status, active.modelName(), active.stage().getName(),
                target == null ? null : target.stage().getName(), versionRatio().ratio(), updatedAt);
    }

    public synchronized String getStatus() {
        return status;
    }

    public synchronized boolean isOnU0() {
        return target == null && active.stage().isU0();
    }

    public LadderStage stageOf(String name) {
        return qualityGate.findStage(name);
    }

    public synchronized String nextVersionTag() {
        versionCounter++;
        return "v" + versionCounter;
    }

    public synchronized String activeModel() {
        return active.modelName();
    }

    private void checkCompletion() {
        long now = clock.millis();
        Map<String, WorkerView> live = liveWorkers(now);
        if (live.isEmpty() || drainingWorker != null && isLive(workers.get(drainingWorker), now)) {
            return;
        }
        if (target != null && live.values().stream().allMatch(w -> target.version().equals(w.versionTag()))) {
            switchTo(target.stage());
        } else if (target == null && STATUS_ROLLING_BACK.equals(status)
                && live.values().stream().allMatch(w -> active.version().equals(w.versionTag()))) {
            changeStatus(STATUS_ROLLED_BACK);
            log.info("event=model.rollback-completed activeVersion={} msg=모든 워커 활성 버전 복귀", active.version());
        }
    }

    private VersionRatio versionRatio() {
        if (target == null) {
            return new VersionRatio(0, liveWorkers(clock.millis()).size());
        }
        Map<String, WorkerView> pulling = new HashMap<>(liveWorkers(clock.millis()));
        if (drainingWorker != null) {
            pulling.remove(drainingWorker);
        }
        int ready = (int) pulling.values().stream().filter(w -> target.version().equals(w.versionTag())).count();
        return new VersionRatio(ready, pulling.size());
    }

    private String desiredVersion() {
        return target != null ? target.version() : active.version();
    }

    private Map<String, WorkerView> liveWorkers(long now) {
        Map<String, WorkerView> live = new HashMap<>();
        workers.forEach((id, w) -> {
            if (isLive(w, now) && w.versionTag() != null) {
                live.put(id, w);
            }
        });
        return live;
    }

    private boolean isLive(WorkerView view, long now) {
        return view != null && now - view.seenAt() <= workerSeenTimeoutMs;
    }

    private void changeStatus(String newStatus) {
        status = newStatus;
        updatedAt = clock.millis();
        store.save(new ModelConfigStore.Snapshot(active.modelName(), active.version(), active.stage().getName(),
                target == null ? null : target.version(), target == null ? null : target.stage().getName(),
                versionCounter, status));
    }

    private static StageResponse toResponse(LadderStage stage) {
        return new StageResponse(stage.getName(), stage.getPrecision(), stage.getWeightUri(), stage.getKvGbPerGpu());
    }

    private static int parseVersion(String version) {
        try {
            return Integer.parseInt(version.replaceAll("\\D", ""));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** 워커가 마지막으로 알려 준 버전과 조회 시각. */
    private record WorkerView(String versionTag, long seenAt) {
    }

    /** 테스트용: 인출 중으로 보는 워커. */
    synchronized List<String> liveWorkerIds() {
        return List.copyOf(liveWorkers(clock.millis()).keySet());
    }
}
