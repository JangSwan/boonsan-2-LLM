package com.llmserving.modelmanager;

/**
 * v2 비율 = v2 준비 워커 수 / 인출 중인 워커 수 (EA VersionRatio, entity).
 *
 * @param readyWorkers   목표 버전으로 준비된(인출 중인) 워커 수
 * @param pullingWorkers 인출 중인 워커 수 (드레인 중인 워커 제외)
 */
public record VersionRatio(int readyWorkers, int pullingWorkers) {

    public double ratio() {
        return pullingWorkers == 0 ? 0.0 : (double) readyWorkers / pullingWorkers;
    }
}
