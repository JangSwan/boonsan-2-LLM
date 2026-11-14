package com.llmserving.membership.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * membership-config (EA 설정 파일): 하트비트 제한 시간, 락 TTL 등.
 *
 * @param instanceId            인스턴스 ID (INSTANCE_ID)
 * @param heartbeatTimeoutSec   이 시간 동안 하트비트가 없으면 장애로 보고 제외한다
 * @param detectIntervalMs      장애 감지 주기
 * @param lockKey               Redis 리더 락 키
 * @param lockTtlSec            리더 락 TTL
 * @param lockRenewIntervalMs   리더 락 갱신 주기 (< 락 TTL)
 * @param reclaimAttempts       lease 회수 시도 횟수
 * @param brokerUrls            broker 주소 (주 브로커, 복제본 순)
 * @param workerUrls            워커 주소. 호스트 이름이 워커 ID다 (예: http://worker-1:8091)
 * @param monitoringUrl         monitoring 주소
 * @param fakeAwsUrl            mock: fake-aws 주소
 * @param aws                   real: AWS 스팟 설정
 */
@ConfigurationProperties("llm.membership")
public record MembershipProperties(
        String instanceId,
        int heartbeatTimeoutSec,
        long detectIntervalMs,
        String lockKey,
        int lockTtlSec,
        long lockRenewIntervalMs,
        int reclaimAttempts,
        List<String> brokerUrls,
        List<String> workerUrls,
        String monitoringUrl,
        String fakeAwsUrl,
        Aws aws) {

    public MembershipProperties {
        if (lockRenewIntervalMs >= lockTtlSec * 1000L) {
            throw new IllegalArgumentException("lock-renew-interval-ms는 lock-ttl-sec보다 짧아야 한다");
        }
    }

    /**
     * @param region           리전
     * @param launchTemplateId 대체 인스턴스 시작 템플릿
     */
    public record Aws(String region, String launchTemplateId) {
    }
}
