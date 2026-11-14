package com.llmserving.frontgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Front Gateway 앱 (stateless). 질문 접수, 요청 ID·버전 태그, SSE 스트림 중계, 중복 토큰 필터.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FrontGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontGatewayApplication.class, args);
    }
}
