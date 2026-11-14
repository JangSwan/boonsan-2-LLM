package com.llmserving.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * client 앱: Web UI(이용자)와 AdminConsole(운영자) 정적 화면을 제공한다 (src/main/resources/static).
 * 화면은 프레임워크 없이 HTML + JavaScript로 만들고, 브라우저가 front-gateway, model-manager, monitoring을 직접 부른다.
 */
@SpringBootApplication
public class ClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }
}
