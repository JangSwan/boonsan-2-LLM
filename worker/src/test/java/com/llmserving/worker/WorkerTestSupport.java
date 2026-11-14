package com.llmserving.worker;

import com.llmserving.common.LeaseResponse;
import com.llmserving.common.Request;
import com.llmserving.common.StageResponse;
import com.llmserving.common.StreamChunk;
import com.llmserving.common.WeightLoadResponse;
import com.llmserving.worker.adapter.LeaseGoneException;
import com.llmserving.worker.adapter.LeasePort;
import com.llmserving.worker.adapter.MockInferenceEngine;
import com.llmserving.worker.adapter.StreamPort;
import com.llmserving.worker.adapter.WeightStorePort;
import com.llmserving.worker.budget.BudgetController;
import com.llmserving.worker.budget.BudgetGuard;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.ModelRuntime;
import com.llmserving.worker.scheduling.EngineEventListener;
import com.llmserving.worker.scheduling.EngineScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** worker 부품을 Spring 없이 엮는 테스트 도구. 가짜 엔진 지연은 0으로 둔다. */
public final class WorkerTestSupport {

    public static final StageResponse U0 = new StageResponse("U0", "BF16", "s3://weights/u0", 6.0);
    public static final StageResponse FP8 = new StageResponse("FP8", "FP8", "s3://weights/fp8", 10.0);

    private WorkerTestSupport() {
    }

    public static WorkerProperties properties() {
        return properties("[[crash]]");
    }

    public static WorkerProperties properties(String crashMarker) {
        return new WorkerProperties("worker-1", false, 8, 300, 2000, 1000, 1000, 4000, 3000,
                List.of("http://b1", "http://b2"), List.of("http://m1"), "http://mm", "http://fg", 1,
                new WorkerProperties.Budget(256, 2048, 3000, 100),
                new WorkerProperties.Credit(0.5, 16000),
                new WorkerProperties.Engine(0, 0, 0, 5, 3, 3, 4, crashMarker, 2000, 0, "", "", 16));
    }

    /** EngineScheduler + ModelRuntime + MockInferenceEngine (지연 0). */
    public static class Engine {
        public final MutableClock clock = new MutableClock();
        public final WorkerProperties properties;
        public final List<StreamChunk> published = Collections.synchronizedList(new ArrayList<>());
        public final ModelRuntime modelRuntime;
        public final EngineScheduler scheduler;
        public final RecordingListener listener = new RecordingListener();

        public Engine() {
            this(properties(), true);
        }

        public Engine(WorkerProperties properties, boolean loaded) {
            this.properties = properties;
            MockInferenceEngine engine = new MockInferenceEngine(properties, clock);
            StreamPort streamPort = published::add;
            WeightStorePort weights = (workerId, stage) -> new WeightLoadResponse(stage, "s3://" + stage, 16, 6.0);
            this.modelRuntime = new ModelRuntime(engine, streamPort, weights, properties);
            BudgetGuard guard = new BudgetGuard(properties);
            this.scheduler = new EngineScheduler(new BudgetController(guard, properties), modelRuntime, clock,
                    properties);
            this.scheduler.subscribe(listener);
            if (loaded) {
                modelRuntime.loadWeights(U0);
            }
        }
    }

    public static class RecordingListener implements EngineEventListener {
        public final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFirstToken(String requestId, int unstartedTokens) {
            events.add("first:" + requestId);
        }

        @Override
        public void onProgress(String requestId) {
            events.add("progress:" + requestId);
        }

        @Override
        public void onCompleted(String requestId) {
            events.add("completed:" + requestId);
        }
    }

    /** 큐 대신 미리 넣어 둔 요청을 내주는 가짜 LeasePort. */
    public static class FakeLeasePort implements LeasePort {
        public final List<Request> queue = Collections.synchronizedList(new ArrayList<>());
        public final List<Integer> requestedCredits = Collections.synchronizedList(new ArrayList<>());
        public final List<String> renewed = Collections.synchronizedList(new ArrayList<>());
        public final List<String> completed = Collections.synchronizedList(new ArrayList<>());
        public final Set<String> gone = ConcurrentHashMap.newKeySet();

        @Override
        public List<LeaseResponse> pull(String workerId, String versionTag, int n) {
            requestedCredits.add(n);
            List<LeaseResponse> leases = new ArrayList<>();
            synchronized (queue) {
                while (!queue.isEmpty() && leases.size() < n) {
                    Request r = queue.remove(0);
                    leases.add(new LeaseResponse(UUID.randomUUID().toString(), r.requestId(), workerId, 0, r, 1));
                }
            }
            return leases;
        }

        @Override
        public void renew(String requestId) {
            if (gone.contains(requestId)) {
                throw new LeaseGoneException(requestId);
            }
            renewed.add(requestId);
        }

        @Override
        public void complete(String requestId) {
            completed.add(requestId);
        }
    }

    public static Request request(String id, int chars) {
        return new Request(id, "v1", (chars + 3) / 4, 0, "가".repeat(chars));
    }

    public static class MutableClock extends Clock {
        private volatile long millis = 1_000_000L;

        public void advanceMs(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
