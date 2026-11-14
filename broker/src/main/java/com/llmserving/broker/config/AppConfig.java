package com.llmserving.broker.config;

import com.llmserving.common.ApiPaths;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * 공통 빈: 시계, 다른 앱 호출용 RestClient 설정(타임아웃, X-Request-Id 전달).
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 모든 외부 호출에 타임아웃을 둔다 (CLAUDE.md 3절). */
    @Bean
    public RestClientCustomizer timeoutAndRequestIdCustomizer(
            @Value("${llm.http.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${llm.http.read-timeout-ms}") long readTimeoutMs) {
        return builder -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
            builder.requestFactory(factory);
            builder.requestInterceptor((request, body, execution) -> {
                String requestId = MDC.get(MdcRequestFilter.MDC_REQUEST_ID);
                if (requestId != null && !request.getHeaders().containsKey(ApiPaths.HEADER_REQUEST_ID)) {
                    request.getHeaders().add(ApiPaths.HEADER_REQUEST_ID, requestId);
                }
                return execution.execute(request, body);
            });
        };
    }
}
