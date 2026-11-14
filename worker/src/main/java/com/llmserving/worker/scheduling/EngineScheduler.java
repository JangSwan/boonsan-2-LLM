package com.llmserving.worker.scheduling;

import com.llmserving.worker.adapter.EngineFailureException;
import com.llmserving.worker.budget.Backlog;
import com.llmserving.worker.budget.BudgetPolicy;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.credit.Capacity;
import com.llmserving.worker.runtime.ModelRuntime;
import com.llmserving.worker.runtime.StepResult;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매 스텝 Decode 전부 + Prefill 조각(합계 ≤ B)으로 스텝을 구성 (EA EngineScheduler, T3).
 * <ul>
 *   <li>B는 BudgetPolicy.budgetFor(backlog)로 받는다 (정책 → BudgetGuard 순).</li>
 *   <li>Prefill 조각은 남은 입력이 적은 요청부터 채운다. 그래서 짧은 요청 S는 다음 스텝에 통째로 끼어들고,
 *       긴 입력 L은 남은 예산만큼 조각으로 읽힌다 (sd2 9~10번).</li>
 *   <li>첫 토큰, 진척, 완료는 EngineEventListener로 알린다 (CreditManager, LeaseRenewer가 구독).</li>
 *   <li>엔진이 비정상 종료하면 생성 시험이 다시 통과할 때까지 남은 여유를 0으로 알린다 (재시작 중에 새 작업을 받지 않는다).</li>
 * </ul>
 */
@Slf4j
@Component
public class EngineScheduler {

    private static final int TTFT_SAMPLES = 200;

    private final BudgetPolicy budgetPolicy;
    private final ModelRuntime modelRuntime;
    private final Clock clock;
    private final int maxSlots;
    private final double kvGbPerRequest;
    private final Map<String, RequestState> jobs = new LinkedHashMap<>();
    private final List<EngineEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<Long> ttftSamples = new ArrayDeque<>();
    private StepPlan inFlightPlan;
    private volatile int lastBudget;
    private volatile boolean engineHealthy = true;
    private volatile long busyMs;
    private final long startedAt;

    public EngineScheduler(BudgetPolicy budgetPolicy, ModelRuntime modelRuntime, Clock clock,
                           WorkerProperties properties) {
        this.budgetPolicy = budgetPolicy;
        this.modelRuntime = modelRuntime;
        this.clock = clock;
        this.maxSlots = properties.maxSlots();
        this.kvGbPerRequest = properties.credit().kvGbPerRequest();
        this.startedAt = clock.millis();
    }

    /** 작업 투입 (sd1 19번, sd2 1·8번). */
    public synchronized void submit(RequestState job) {
        jobs.putIfAbsent(job.getRequestId(), job);
    }

    /** 다음 스텝 구성 (sd2 2~5번). */
    public synchronized StepPlan buildStep() {
        List<String> decodeIds = new ArrayList<>();
        List<RequestState> prefilling = new ArrayList<>();
        int unstarted = 0;
        for (RequestState job : jobs.values()) {
            if (job.isDecoding()) {
                decodeIds.add(job.getRequestId());
            } else {
                prefilling.add(job);
                unstarted += job.getUnstartedTokens();
            }
        }
        int budget = budgetPolicy.budgetFor(new Backlog(unstarted, decodeIds.size()));
        lastBudget = budget;
        int remaining = budget - decodeIds.size();

        prefilling.sort(Comparator.comparingInt(RequestState::getUnstartedTokens)
                .thenComparingLong(RequestState::getSubmittedAt));
        Map<String, Integer> prefillChunks = new LinkedHashMap<>();
        Map<String, String> prompts = new LinkedHashMap<>();
        for (RequestState job : prefilling) {
            int chunk = Math.min(job.getUnstartedTokens(), Math.max(0, remaining));
            if (chunk == 0 && job.getUnstartedTokens() > 0) {
                continue;  // 이번 스텝 예산을 다 썼다
            }
            prefillChunks.put(job.getRequestId(), chunk);
            if (!job.isPrefillStarted()) {
                prompts.put(job.getRequestId(), job.getPrompt());
                job.markPrefillStarted();
            }
            job.consumePrefill(chunk);
            remaining -= chunk;
        }
        StepPlan plan = new StepPlan(decodeIds, prefillChunks, budget, prompts);
        inFlightPlan = plan;
        return plan;
    }

    /** 스텝 결과 반영 (sd2 13번). 이벤트는 잠금 밖에서 알린다. */
    public void onStepDone(StepResult result) {
        StepPlan plan;
        int unstartedAfter;
        Set<String> firstTokens = new HashSet<>(result.prefillDoneIds());
        Set<String> finished = new HashSet<>(result.finishedIds());
        synchronized (this) {
            plan = inFlightPlan;
            inFlightPlan = null;
            long now = clock.millis();
            for (String id : result.prefillDoneIds()) {
                RequestState job = jobs.get(id);
                if (job != null) {
                    job.startDecode();
                    recordTtft(now - job.getSubmittedAt());
                }
            }
            result.finishedIds().forEach(jobs::remove);
            unstartedAfter = unstartedTokens();
        }
        List<String> members = new ArrayList<>();
        if (plan != null) {
            members.addAll(plan.decodeIds());
            members.addAll(plan.prefillChunks().keySet());
        }
        for (String id : members) {
            if (firstTokens.contains(id)) {
                listeners.forEach(l -> l.onFirstToken(id, unstartedAfter));
            } else {
                listeners.forEach(l -> l.onProgress(id));
            }
            if (finished.contains(id)) {
                listeners.forEach(l -> l.onCompleted(id));
            }
        }
    }

    public void subscribe(EngineEventListener listener) {
        listeners.add(listener);
    }

    /** 남은 슬롯 · KV · 미시작 토큰 (sd1 10~11번). */
    public synchronized Capacity freeCapacity() {
        if (!engineHealthy) {
            return new Capacity(0, 0, unstartedTokens());
        }
        int freeSlots = Math.max(0, maxSlots - jobs.size());
        double freeKvGb = modelRuntime.kvCapacityGb() - jobs.size() * kvGbPerRequest;
        return new Capacity(freeSlots, freeKvGb, unstartedTokens());
    }

    /** 엔진 루프: 할 일이 있으면 스텝 하나를 실행한다. */
    @Scheduled(fixedDelayString = "${llm.worker.engine.idle-wait-ms}")
    public void runStep() {
        if (!engineHealthy) {
            if (!modelRuntime.generationTest()) {
                return;
            }
            engineHealthy = true;
            log.info("event=engine.recovered msg=엔진 생성 시험 통과, 다시 작업을 받음");
        }
        StepPlan plan = buildStep();
        if (plan.isEmpty()) {
            return;
        }
        long start = clock.millis();
        try {
            onStepDone(modelRuntime.execute(plan));
        } catch (EngineFailureException e) {
            onEngineFailure(e);
        } finally {
            busyMs += clock.millis() - start;
        }
    }

    /**
     * 엔진 비정상 종료: 엔진 안의 요청이 모두 사라진다. 진척 보고가 끊기므로 lease 갱신이 멈추고,
     * lease가 만료되면 broker의 재시도·격리 경로가 처리한다 (sd4 8~9번). 완료 ack는 보내지 않는다.
     */
    private synchronized void onEngineFailure(EngineFailureException e) {
        List<String> lost = List.copyOf(jobs.keySet());
        jobs.clear();
        inFlightPlan = null;
        engineHealthy = false;
        log.error("event=engine.failed lost={} msg=엔진 비정상 종료, 진행 중 요청의 진척 보고 중단: {}", lost, e.getMessage());
    }

    public synchronized boolean hasJob(String requestId) {
        return jobs.containsKey(requestId);
    }

    public synchronized int activeSessions() {
        return jobs.size();
    }

    public int lastBudget() {
        return lastBudget;
    }

    public synchronized long ttftP95Ms() {
        if (ttftSamples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(ttftSamples);
        sorted.sort(Long::compare);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
    }

    /** 엔진이 스텝을 실행한 시간 비율 (mock의 GPU 사용률 추정). */
    public double gpuUtilization() {
        long elapsed = Math.max(1, clock.millis() - startedAt);
        return Math.min(1.0, (double) busyMs / elapsed);
    }

    private int unstartedTokens() {
        return jobs.values().stream().filter(j -> !j.isDecoding()).mapToInt(RequestState::getUnstartedTokens).sum();
    }

    private void recordTtft(long ms) {
        ttftSamples.addLast(ms);
        if (ttftSamples.size() > TTFT_SAMPLES) {
            ttftSamples.removeFirst();
        }
    }
}
