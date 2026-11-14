package com.llmserving.modelmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Model Manager 앱 (U1). 모델 교체, 사다리 단계, 품질 게이트, 버전 비율, 롤백.
 * 다른 서브시스템을 호출하지 않는다: 워커가 설정을 조회하고, AdminConsole이 상태를 조회한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ModelManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelManagerApplication.class, args);
    }
}
