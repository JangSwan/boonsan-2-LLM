package com.llmserving.worker.adapter;

import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ModelConfigResponse;

/**
 * model-manager ModelConfig 호출 포트 (WorkerAgent: 설정 감지, 드레인 시작·준비 완료 보고).
 */
public interface ModelConfigPort {

    /** 설정 조회. 현재 내 버전을 함께 알려 Model Manager가 버전별 인출 워커를 셀 수 있게 한다. */
    ModelConfigResponse fetch(String workerId, String versionTag);

    DrainStartResponse drainStart(String workerId);

    void ready(String workerId);
}
