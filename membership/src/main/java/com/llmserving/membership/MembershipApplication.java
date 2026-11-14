package com.llmserving.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Membership 앱 (R3). 하트비트 감지, 스팟 회수 통지 처리, lease 회수, 재보충, 복구 알림, 리더 락.
 * 두 인스턴스로 띄우고 리더 락을 가진 1대만 제외·회수·재보충을 실행한다.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MembershipApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipApplication.class, args);
    }
}
