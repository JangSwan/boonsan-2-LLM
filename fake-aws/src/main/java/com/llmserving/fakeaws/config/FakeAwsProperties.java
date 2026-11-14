package com.llmserving.fakeaws.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * fake-aws 설정.
 *
 * @param instanceId             인스턴스 ID
 * @param membershipUrls         membership 주소 (두 인스턴스 모두에 통지)
 * @param spareInstanceUrls      대체 인스턴스로 내줄 예비 워커 주소. 호스트 이름(또는 "ID=주소"의 ID)이 인스턴스 ID다
 * @param bootDelayMs            인스턴스 확보·부팅을 흉내 내는 지연
 * @param idempotencyWindowSec   같은 중복 방지 키를 같은 요청으로 보는 시간
 */
@ConfigurationProperties("llm.fake-aws")
public record FakeAwsProperties(
        String instanceId,
        List<String> membershipUrls,
        List<String> spareInstanceUrls,
        long bootDelayMs,
        int idempotencyWindowSec) {
}
