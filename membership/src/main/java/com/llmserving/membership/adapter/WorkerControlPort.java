package com.llmserving.membership.adapter;

/** worker WorkerAgent.stopPull 호출 포트 (NoticeHandler). 멱등. */
public interface WorkerControlPort {

    void stopPull(String workerId);
}
