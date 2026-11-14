package com.llmserving.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 앱 (C2, T3, U1, R3). 크레딧 pull, lease 갱신, 스텝 예산, 엔진 스케줄링, 모델 실행, 하트비트.
 * worker-1 ~ worker-5와 예비 worker-spare를 같은 앱으로 띄운다.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
