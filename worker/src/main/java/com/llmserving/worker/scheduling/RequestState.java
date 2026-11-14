package com.llmserving.worker.scheduling;

import lombok.Getter;

/**
 * 워커 안에서 진행 중인 요청 상태 (EA RequestState, entity). 상태 변경은 의미 있는 메서드로만 한다.
 */
@Getter
public class RequestState {

    public static final String PHASE_PREFILL = "PREFILL";
    public static final String PHASE_DECODE = "DECODE";

    private final String requestId;
    private final String versionTag;
    private int unstartedTokens;
    private String phase = PHASE_PREFILL;
    private final String prompt;
    private final long submittedAt;
    private boolean prefillStarted;

    public RequestState(String requestId, String versionTag, int inputTokens, String prompt, long submittedAt) {
        this.requestId = requestId;
        this.versionTag = versionTag;
        this.unstartedTokens = Math.max(0, inputTokens);
        this.prompt = prompt;
        this.submittedAt = submittedAt;
    }

    /** 이번 스텝에서 Prefill 조각 n토큰을 읽는다. */
    public void consumePrefill(int n) {
        unstartedTokens = Math.max(0, unstartedTokens - n);
    }

    /** 첫 Prefill 조각이 스텝에 들어갔다 (본문을 엔진에 넘겼다). */
    public void markPrefillStarted() {
        prefillStarted = true;
    }

    /** Prefill이 끝나 Decode로 넘어간다. */
    public void startDecode() {
        unstartedTokens = 0;
        phase = PHASE_DECODE;
    }

    public boolean isDecoding() {
        return PHASE_DECODE.equals(phase);
    }
}
