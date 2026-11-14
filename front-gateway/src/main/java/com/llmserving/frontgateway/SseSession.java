package com.llmserving.frontgateway;

import lombok.Getter;

/**
 * 요청 하나의 SSE 연결 상태 (EA SseSession, entity). 상태 변경은 의미 있는 메서드로만 한다.
 */
@Getter
public class SseSession {

    private final String sessionId;
    private final String requestId;
    private volatile int lastSeq = -1;
    private volatile int lastPosition = -1;

    public SseSession(String sessionId, String requestId) {
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    /** 조각을 보냈다. */
    public void advance(int seq) {
        lastSeq = Math.max(lastSeq, seq);
    }

    /** 순번 안내를 보냈다. */
    public void positionSent(int position) {
        lastPosition = position;
    }

    /** 첫 토큰을 받았는가 (그 뒤로는 순번을 안내하지 않는다). */
    public boolean isStreaming() {
        return lastSeq >= 0;
    }
}
