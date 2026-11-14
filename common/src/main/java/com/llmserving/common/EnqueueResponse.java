package com.llmserving.common;

/**
 * enqueue 응답 (Broker AdmissionController → Front Gateway). 복제가 확인된 뒤에 보낸다.
 *
 * @param requestId 요청 ID
 * @param position  대기 순번
 * @param epoch     응답한 주 브로커의 epoch (fencing)
 */
public record EnqueueResponse(
        String requestId,
        int position,
        long epoch) {
}
