package com.llmserving.monitoring.adapter;

import com.llmserving.common.QualitySample;

/** model-manager QualityGate.observe 호출 포트 (SampleForwarder). */
public interface QualitySamplePort {

    void send(QualitySample sample);
}
