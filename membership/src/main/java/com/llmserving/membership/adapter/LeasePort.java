package com.llmserving.membership.adapter;

/** broker LeaseManager.reclaim 호출 포트 (LeaseReclaimer). 주 브로커가 응답하지 않으면 복제본으로 전환한다. */
public interface LeasePort {

    /** 워커의 lease를 회수하고, 공용 큐로 되돌린 작업 수를 돌려준다. 멱등. */
    int reclaim(String workerId);
}
