package com.llmserving.worker.runtime;

import com.llmserving.common.StreamChunk;
import java.util.List;

/**
 * 스텝 결과 토큰과 Prefill을 끝낸 요청 (EA StepResult, entity).
 *
 * @param chunks         이번 스텝에서 나온 토큰
 * @param prefillDoneIds Prefill을 끝낸 요청 (첫 토큰이 나온 요청)
 * @param finishedIds    생성이 끝난 요청 (완료 ack와 SSE 종료의 근거)
 */
public record StepResult(
        List<StreamChunk> chunks,
        List<String> prefillDoneIds,
        List<String> finishedIds) {
}
