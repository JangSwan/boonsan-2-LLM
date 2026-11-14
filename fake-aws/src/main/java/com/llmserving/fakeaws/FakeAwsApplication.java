package com.llmserving.fakeaws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 가짜 AWS (mock 모드). 스팟 회수 통지 발송, 인스턴스 재생성 요청 처리(예비 Worker에 기동 신호), 기동 완료 통지.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FakeAwsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeAwsApplication.class, args);
    }
}
