package com.llmserving.modelmanager;

/**
 * 모델 이름과 버전 (EA ModelVersion, entity). 단계를 포함한다.
 *
 * @param modelName 모델 이름
 * @param version   버전 태그 (v1, v2, ...). 요청과 워커가 이 태그로 짝지어진다
 * @param stage     사다리 단계
 */
public record ModelVersion(String modelName, String version, LadderStage stage) {
}
