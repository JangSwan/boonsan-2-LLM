package com.llmserving.monitoring.adapter;

import com.llmserving.common.QueueMetricsResponse;
import java.util.Optional;

/** broker 큐 지표 조회 포트. 응답이 없으면 빈 값 (이번 주기는 건너뛴다). */
public interface QueueMetricsPort {

    Optional<QueueMetricsResponse> fetch();
}
