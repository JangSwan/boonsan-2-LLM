package com.llmserving.monitoring.adapter;

import com.llmserving.common.WorkerMetricsResponse;
import java.util.List;

/** worker 지표 조회 포트. 응답한 워커의 지표만 돌려준다. */
public interface WorkerMetricsPort {

    List<WorkerMetricsResponse> fetchAll();
}
