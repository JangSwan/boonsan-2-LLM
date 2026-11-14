package com.llmserving.monitoring;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.QualitySample;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 응답 품질 샘플 입력 입구 → SampleForwarder. mock 모드에는 실제 응답 평가기가 없으므로 시연에서 품질 샘플을 여기로 넣는다.
 */
@RestController
@RequiredArgsConstructor
public class QualitySampleController {

    private final SampleForwarder sampleForwarder;

    @PostMapping(ApiPaths.Monitoring.QUALITY_SAMPLES)
    public ResponseEntity<Void> sample(@RequestBody QualitySample sample) {
        if (sample.versionTag() == null || sample.versionTag().isBlank()) {
            throw new IllegalArgumentException("versionTag가 필요합니다");
        }
        sampleForwarder.forward(sample);
        return ResponseEntity.accepted().build();
    }
}
