package com.llmserving.monitoring;

import com.llmserving.monitoring.config.MonitoringProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 장애, 복구 알림을 운영자 화면에 제공 (EA AlertService). 최근 알림을 메모리에 보관한다. */
@Slf4j
@Component
public class AlertService {

    private final int maxAlerts;
    private final Deque<AlertEvent> alerts = new ArrayDeque<>();

    public AlertService(MonitoringProperties properties) {
        this.maxAlerts = properties.maxAlerts();
    }

    public synchronized void raise(AlertEvent event) {
        alerts.addFirst(event);
        while (alerts.size() > maxAlerts) {
            alerts.removeLast();
        }
        log.info("event=alert.raised kind={} msg={}", event.kind(), event.message());
    }

    /** 최근 알림부터. */
    public synchronized List<AlertEvent> getAlerts() {
        return new ArrayList<>(alerts);
    }
}
