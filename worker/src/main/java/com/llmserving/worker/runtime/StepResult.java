package com.llmserving.worker.runtime;

import com.llmserving.common.StreamChunk;
import java.util.List;

/**
 * 스텝 결과 토큰과 Prefill을 끝낸 요청 (EA StepResult, entity).
 *
 * @param chunks         이번 스텝에서 나온 토큰
 * @param prefillDoneIds Prefill을 끝낸 요청 (첫 토큰이 나온 요청)
 * @param finishedIds    생성이 끝난 요청 (완료 ack와 SSE 종료의 근거)
 * @param failedIds      엔진이 그 요청만 처리하지 못한 요청 (예: 입력이 엔진 한도를 넘어 거절됨). 엔진은 정상이므로
 *                       다른 요청은 계속 진행하고, 이 요청은 진척 보고가 끊겨 sd4의 lease 만료 · 재시도 · 격리 경로로 간다.
 *                       EA에 없는 속성이라 EA 반영이 필요하다 (docs/design.md 5절 "추론 엔진 계약").
 */
public record StepResult(
        List<StreamChunk> chunks,
        List<String> prefillDoneIds,
        List<String> finishedIds,
        List<String> failedIds) {

    /** 실패한 요청이 없는 스텝 결과. */
    public StepResult(List<StreamChunk> chunks, List<String> prefillDoneIds, List<String> finishedIds) {
        this(chunks, prefillDoneIds, finishedIds, List.of());
    }
}
