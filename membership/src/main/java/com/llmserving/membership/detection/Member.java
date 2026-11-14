package com.llmserving.membership.detection;

import lombok.Getter;

/**
 * 워커 1대의 멤버십 상태 (EA Member, entity). 상태 변경은 의미 있는 메서드로만 한다.
 */
@Getter
public class Member {

    public static final String ACTIVE = "ACTIVE";
    public static final String RECLAIM_SCHEDULED = "RECLAIM_SCHEDULED";
    public static final String EXCLUDED = "EXCLUDED";

    private final String workerId;
    private volatile String state = ACTIVE;
    private volatile String versionTag;
    private volatile long lastHeartbeat;
    private volatile boolean selfTestPassed;

    public Member(String workerId) {
        this.workerId = workerId;
    }

    /** 하트비트를 받았다. 제외됐던 워커가 다시 보고하면 다시 활성으로 본다. */
    public void heartbeat(String versionTag, boolean selfTestPassed, long at) {
        this.versionTag = versionTag;
        this.selfTestPassed = selfTestPassed;
        this.lastHeartbeat = at;
        if (EXCLUDED.equals(state)) {
            state = ACTIVE;
        }
    }

    public void scheduleReclaim() {
        if (!EXCLUDED.equals(state)) {
            state = RECLAIM_SCHEDULED;
        }
    }

    public void exclude() {
        state = EXCLUDED;
    }

    public boolean isExcluded() {
        return EXCLUDED.equals(state);
    }

    /** 생성 시험을 통과했고 제외되지 않았는가 (합류 완료). */
    public boolean isJoined() {
        return !isExcluded() && selfTestPassed;
    }

    public boolean isOverdue(long now, long timeoutMs) {
        return !isExcluded() && now - lastHeartbeat > timeoutMs;
    }
}
