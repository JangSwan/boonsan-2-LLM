package com.llmserving.common;

/**
 * enqueue 응답 (AdmissionController.enqueue). 복제가 확인된 뒤에만 응답한다 (sd5).
 *
 * @param requestId 요청 ID
 * @param position  대기 순번 (1부터)
 * @param epoch     응답한 주 브로커의 epoch (sd5 fencing)
 */
public record EnqueueResponse(
        String requestId,
        int position,
        long epoch) {
}
