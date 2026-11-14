package com.llmserving.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * broker-config (EA 설정 파일). 상한값과 시간 값은 코드에 박지 않고 여기서 읽는다.
 *
 * @param instanceId                  인스턴스 ID (INSTANCE_ID)
 * @param role                        시작할 때의 역할 (BROKER_ROLE: primary / replica)
 * @param leaseTtlSec                 lease TTL
 * @param concurrencyCap              전체 동시 실행 상한 (4대분 = 4 × 워커당 슬롯)
 * @param retryLimit                  재시도 상한
 * @param expireCheckIntervalMs       lease 만료 검사 주기
 * @param primaryLockKey              Redis broker-primary 락 키
 * @param primaryLockRenewIntervalMs  broker-primary 락 갱신 주기 (< 락 TTL)
 * @param primaryLockTtlSec           broker-primary 락 TTL (≤ promoteAfterSec)
 * @param promoteAfterSec             복제본이 주 브로커와 연락이 끊긴 뒤 승격을 시도하기까지의 시간 X
 * @param replicaUrl                  복제본 broker 주소 (주 브로커만 설정)
 * @param frontGatewayUrl             실패 통지 대상 Front Gateway 주소
 * @param failureNotifyAttempts       실패 통지 시도 횟수
 */
@ConfigurationProperties("llm.broker")
public record BrokerProperties(
        String instanceId,
        String role,
        int leaseTtlSec,
        int concurrencyCap,
        int retryLimit,
        long expireCheckIntervalMs,
        String primaryLockKey,
        long primaryLockRenewIntervalMs,
        int primaryLockTtlSec,
        int promoteAfterSec,
        String replicaUrl,
        String frontGatewayUrl,
        int failureNotifyAttempts) {

    public BrokerProperties {
        // CLAUDE.md 6절 sd5 시간 조건: 락 갱신 주기 < 락 TTL ≤ X
        if (primaryLockRenewIntervalMs >= primaryLockTtlSec * 1000L) {
            throw new IllegalArgumentException("primary-lock-renew-interval-ms는 primary-lock-ttl-sec보다 짧아야 한다");
        }
        if (primaryLockTtlSec > promoteAfterSec) {
            throw new IllegalArgumentException("primary-lock-ttl-sec는 promote-after-sec 이하여야 한다");
        }
        if (concurrencyCap <= 0 || retryLimit <= 0 || leaseTtlSec <= 0) {
            throw new IllegalArgumentException("concurrency-cap, retry-limit, lease-ttl-sec는 양수여야 한다");
        }
    }

    public boolean startsAsPrimary() {
        return "primary".equalsIgnoreCase(role);
    }

    public boolean hasReplica() {
        return replicaUrl != null && !replicaUrl.isBlank();
    }
}
