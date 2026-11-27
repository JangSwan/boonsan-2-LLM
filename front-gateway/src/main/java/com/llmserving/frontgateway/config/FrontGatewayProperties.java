package com.llmserving.frontgateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * front-gateway 설정.
 *
 * @param instanceId               인스턴스 ID
 * @param brokerUrls               broker 주소 (주 브로커, 복제본 순)
 * @param modelManagerUrl          model-manager 주소
 * @param positionPollIntervalMs   대기 순번 갱신 조회 주기
 * @param ratioPollIntervalMs      v2 비율 조회 주기
 * @param sseTimeoutMs             SSE 연결 최대 유지 시간
 * @param charsPerToken            입력 토큰 수 추정 (문자 수 / 이 값, 워커 가짜 엔진과 같은 값)
 * @param defaultVersion           Model Manager에 닿기 전 쓰는 활성 버전 태그
 * @param corsOrigins              브라우저 client 출처
 * @param brokerRetryBudgetMs      broker 호출을 다시 시도하는 최대 시간 (접수는 거절하지 않으므로 이 시간 동안 기다린다)
 * @param brokerRetryBackoffMs     모든 주소가 실패했을 때 다시 시도하기 전 쉬는 시간
 * @param wideCharTokens           한글·한자·가나 1글자를 몇 토큰으로 셀지 (0 이하면 다른 글자처럼 charsPerToken으로 나눈다).
 *                                 Llama 토크나이저는 한글을 대략 1글자 1토큰 안팎으로 쪼갠다 (트러블슈팅 R4)
 */
@ConfigurationProperties("llm.front-gateway")
public record FrontGatewayProperties(
        String instanceId,
        List<String> brokerUrls,
        String modelManagerUrl,
        long positionPollIntervalMs,
        long ratioPollIntervalMs,
        long sseTimeoutMs,
        int charsPerToken,
        String defaultVersion,
        List<String> corsOrigins,
        long brokerRetryBudgetMs,
        long brokerRetryBackoffMs,
        double wideCharTokens) {
}
