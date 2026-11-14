package com.llmserving.frontgateway.adapter;

import com.llmserving.common.RatioResponse;

/**
 * model-manager ModelConfig의 버전 비율 조회 포트. 구현: frontgateway.ModelConfigClient (EA).
 */
public interface ModelConfigPort {

    /** v2 비율 (EA ModelConfigClient.getRatio). */
    double getRatio();

    /** 비율과 함께 활성·목표 버전 태그 이름. */
    RatioResponse currentRatio();
}
