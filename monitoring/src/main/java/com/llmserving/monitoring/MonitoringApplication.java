package com.llmserving.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Monitoring 앱. 지표 수집, 장애·복구 알림, 품질 샘플 전달. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MonitoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitoringApplication.class, args);
    }
}
