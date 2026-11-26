package com.llmserving.modelmanager.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 브라우저 client(AdminConsole) 출처에 CORS를 연다. */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ModelManagerProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 출처 목록(쉼표 구분)의 각 항목은 정확한 출처나 패턴(예: http://*:8140)이다.
                // 클라우드 서버는 켤 때마다 공인 IP가 바뀌므로 패턴을 쓸 수 있게 한다 (docs/cloud-setup.md)
                .allowedOriginPatterns(properties.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
