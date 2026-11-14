package com.llmserving.monitoring;

import com.llmserving.common.QualitySample;
import com.llmserving.monitoring.adapter.QualitySamplePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 응답 샘플을 Quality Gate로 전달 (EA SampleForwarder). QualitySamplePort를 사용하는 control이다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleForwarder {

    private final QualitySamplePort qualitySamplePort;

    /** sd6 26번. */
    public void forward(QualitySample sample) {
        qualitySamplePort.send(sample);
        log.info("event=quality.sample-forwarded requestId={} versionTag={} score={} msg=품질 샘플 전달",
                sample.requestId(), sample.versionTag(), sample.score());
    }
}
