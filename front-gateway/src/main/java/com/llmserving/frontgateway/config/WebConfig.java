package com.llmserving.frontgateway.config;

import java.util.Random;
import java.util.random.RandomGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 브라우저 client 출처에 CORS를 연다 (CLAUDE.md 3절). 버전 태그 비율용 난수 생성기를 등록한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final FrontGatewayProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    /** java.util.Random은 스레드 안전하고 모든 JRE에 있다 (jre 이미지에는 RandomGenerator.getDefault()의 구현이 없을 수 있다). */
    @Bean
    public RandomGenerator versionRandom() {
        return new Random();
    }
}
