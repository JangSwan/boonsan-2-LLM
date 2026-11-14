package com.llmserving.monitoring.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * monitoring 설정.
 *
 * @param instanceId        인스턴스 ID
 * @param collectIntervalMs 지표 수집 주기
 * @param historySize       지표 이름별로 보관하는 개수
 * @param maxAlerts         보관하는 알림 개수
 * @param brokerUrls        broker 주소 (주 브로커, 복제본 순)
 * @param workerUrls        워커 주소. 호스트 이름이 워커 ID다
 * @param modelManagerUrl   model-manager 주소
 * @param corsOrigins       브라우저 client 출처
 */
@ConfigurationProperties("llm.monitoring")
public record MonitoringProperties(
        String instanceId,
        long collectIntervalMs,
        int historySize,
        int maxAlerts,
        List<String> brokerUrls,
        List<String> workerUrls,
        String modelManagerUrl,
        List<String> corsOrigins) {
}
