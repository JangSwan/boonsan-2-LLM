package com.llmserving.common;

/**
 * 드레인 시작 보고 응답 (WorkerAgent → ModelConfig.onDrainStart). 워커는 한 대씩 교체하므로
 * 다른 워커가 드레인 중이면 granted = false다.
 *
 * @param workerId      보고한 워커 ID
 * @param granted       드레인 허가 여부
 * @param lastOfVersion 이 워커가 현재 버전을 인출하는 마지막 워커인지. true면 남은 작업을 다 가져간 뒤 멈춘다
 */
public record DrainStartResponse(
        String workerId,
        boolean granted,
        boolean lastOfVersion) {
}
