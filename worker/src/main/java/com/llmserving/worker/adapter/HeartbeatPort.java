package com.llmserving.worker.adapter;

import com.llmserving.common.WorkerStatus;

/**
 * 하트비트 · 상태 보고 포트 (WorkerAgent → membership HeartbeatController → HealthDetector).
 * Broker에는 보내지 않는다. 두 membership 인스턴스 모두에 보내고, 응답 없는 쪽은 건너뛴다.
 */
public interface HeartbeatPort {

    void send(WorkerStatus status);
}
