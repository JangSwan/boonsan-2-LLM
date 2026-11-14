package com.llmserving.modelmanager.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * model-manager 설정. model-config(EA 설정 파일)의 처음 값과 게이트·롤백 기준.
 *
 * @param instanceId          인스턴스 ID
 * @param activeModel         model-config 처음 값: 활성 모델
 * @param activeStage         model-config 처음 값: 활성 단계
 * @param activeVersion       model-config 처음 값: 활성 버전 태그
 * @param targetStage         model-config 처음 값: 목표 단계 (비우면 교체 없음)
 * @param configFile          model-config를 저장하는 파일 (교체·롤백 결과를 여기 한 곳에 기록)
 * @param workerSeenTimeoutMs 이 시간 동안 설정 조회가 없는 워커는 인출 중인 워커로 세지 않는다
 * @param drainTimeoutMs      드레인 허가를 받은 워커가 이 시간 안에 준비를 보고하지 않으면 다른 워커에 허가한다
 * @param gate                품질 게이트 기준
 * @param rollback            운영 중 품질 이상 판정 기준
 * @param store               가중치 저장소 (단계 목록, mock 지연, real S3 버킷)
 * @param corsOrigins         브라우저 client 출처
 */
@ConfigurationProperties("llm.model-manager")
public record ModelManagerProperties(
        String instanceId,
        String activeModel,
        String activeStage,
        String activeVersion,
        String targetStage,
        String configFile,
        long workerSeenTimeoutMs,
        long drainTimeoutMs,
        Gate gate,
        Rollback rollback,
        Store store,
        List<String> corsOrigins) {

    /**
     * @param minScore          이 점수 이상인 단계만 교체를 허용한다
     * @param qualityScores     단계별 후보 평가 품질 점수 (mock: 사전 측정값)
     * @param sessionsPerDollar 단계별 처리량 지표
     */
    public record Gate(double minScore, Map<String, Double> qualityScores, Map<String, Double> sessionsPerDollar) {
    }

    /**
     * @param threshold 버전별 최근 샘플 평균이 이 값 미만이면 U0로 되돌린다
     * @param window    평균을 내는 샘플 수
     */
    public record Rollback(double threshold, int window) {
    }

    /**
     * @param loadDelayMs mock: 가중치 적재 지연
     * @param s3Bucket    real: 가중치 버킷
     * @param region      real: 리전
     * @param stages      사다리 단계 목록
     */
    public record Store(long loadDelayMs, String s3Bucket, String region, List<StageConfig> stages) {
    }

    /**
     * @param name       단계 이름 (U0, FP8, SMALL)
     * @param weightUri  가중치 위치
     * @param kvGbPerGpu GPU당 KV 용량
     */
    public record StageConfig(String name, String weightUri, double kvGbPerGpu) {
    }
}
