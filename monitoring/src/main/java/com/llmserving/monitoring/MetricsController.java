package com.llmserving.monitoring;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.MetricResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MetricsCollector의 REST 입구 (AdminConsole.showMetrics). 위임만 한다. */
@RestController
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsCollector metricsCollector;

    @GetMapping(ApiPaths.Monitoring.METRICS)
    public List<MetricResponse> metrics(@RequestParam(required = false) String name) {
        return metricsCollector.query(name).stream()
                .map(m -> new MetricResponse(m.name(), m.value(), m.at())).toList();
    }
}
