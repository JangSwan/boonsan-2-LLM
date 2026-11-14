package com.llmserving.worker.agent;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.BootResponse;
import com.llmserving.common.StopPullResponse;
import com.llmserving.common.WorkerMetricsResponse;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.credit.Capacity;
import com.llmserving.worker.scheduling.EngineScheduler;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker의 REST 입구: 인출 중지(Membership WorkerControlPort → WorkerAgent), 기동 신호(fake-aws),
 * 지표 수집(Monitoring WorkerMetricsPort → EngineScheduler). 위임만 한다.
 */
@RestController
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerAgent workerAgent;
    private final EngineScheduler engineScheduler;
    private final WorkerProperties properties;
    private final Clock clock;

    @PostMapping(ApiPaths.Worker.STOP_PULL)
    public StopPullResponse stopPull() {
        workerAgent.stopPull();
        return new StopPullResponse(properties.instanceId(), workerAgent.isPulling());
    }

    @PostMapping(ApiPaths.Worker.BOOT)
    public BootResponse boot() {
        workerAgent.boot();
        return new BootResponse(properties.instanceId(), workerAgent.getState());
    }

    @GetMapping(ApiPaths.Worker.METRICS)
    public WorkerMetricsResponse metrics() {
        Capacity capacity = engineScheduler.freeCapacity();
        return new WorkerMetricsResponse(properties.instanceId(), workerAgent.getVersionTag(),
                engineScheduler.activeSessions(), capacity.freeSlots(), capacity.freeKvGb(),
                capacity.unstartedTokens(), engineScheduler.lastBudget(), engineScheduler.ttftP95Ms(),
                engineScheduler.gpuUtilization(), clock.millis());
    }
}
