package com.llmserving.broker.queue;

import com.llmserving.broker.lease.LeaseLimiter;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.QueueMetricsResponse;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 큐 길이 수집 입구 (Monitoring QueueMetricsPort → SharedQueue). 읽기만 하고 위임한다.
 */
@RestController
@RequiredArgsConstructor
public class QueueMetricsController {

    private final SharedQueue sharedQueue;
    private final QuarantineQueue quarantineQueue;
    private final LeaseLimiter leaseLimiter;
    private final Clock clock;

    @GetMapping(ApiPaths.Broker.QUEUE_METRICS)
    public QueueMetricsResponse metrics() {
        return new QueueMetricsResponse(sharedQueue.length(), quarantineQueue.list().size(),
                leaseLimiter.getActiveLeases(), leaseLimiter.getConcurrencyCap(), clock.millis(),
                sharedQueue.shortLength(), sharedQueue.longLength());
    }
}
