package com.llmserving.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Queue Broker 앱 (C2). 접수, 공용 큐, lease, 4대분 상한, 재시도·격리, 복제.
 * 같은 앱을 BROKER_ROLE=primary / replica 두 인스턴스로 띄운다.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }
}
