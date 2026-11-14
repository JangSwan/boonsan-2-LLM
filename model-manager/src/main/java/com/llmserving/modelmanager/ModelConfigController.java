package com.llmserving.modelmanager;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.ModelConfigResponse;
import com.llmserving.common.QualitySample;
import com.llmserving.common.RatioResponse;
import com.llmserving.common.WeightLoadRequest;
import com.llmserving.common.WeightLoadResponse;
import com.llmserving.modelmanager.adapter.ModelStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Model Manager의 REST 입구 (CLAUDE.md 3절 Port 목록): 비율 조회·설정 감지·드레인·준비 보고 → ModelConfig,
 * 가중치 적재 → ModelStorePort, 품질 샘플 → QualityGate. 위임만 한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ModelConfigController {

    private static final double BYTES_PER_GB = 1024.0 * 1024 * 1024;

    private final ModelConfig modelConfig;
    private final ModelStorePort modelStore;
    private final QualityGate qualityGate;

    @GetMapping(ApiPaths.ModelManager.RATIO)
    public RatioResponse ratio() {
        return modelConfig.ratioResponse();
    }

    @GetMapping(ApiPaths.ModelManager.CONFIG)
    public ModelConfigResponse config(@RequestParam(required = false) String workerId,
                                      @RequestParam(required = false) String versionTag) {
        return modelConfig.configFor(workerId, versionTag);
    }

    @PostMapping(ApiPaths.ModelManager.WORKER_DRAIN_START)
    public DrainStartResponse drainStart(@PathVariable String workerId) {
        return modelConfig.onDrainStart(workerId);
    }

    @PostMapping(ApiPaths.ModelManager.WORKER_READY)
    public ResponseEntity<Void> ready(@PathVariable String workerId) {
        modelConfig.onReady(workerId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping(ApiPaths.ModelManager.WEIGHTS_LOAD)
    public WeightLoadResponse loadWeights(@RequestBody WeightLoadRequest request) {
        LadderStage stage = modelConfig.stageOf(request.stageName());
        byte[] weights = modelStore.loadWeights(stage);
        log.info("event=model.weights-served workerId={} stage={} msg=가중치 전달", request.workerId(), stage.getName());
        return new WeightLoadResponse(stage.getName(), stage.getWeightUri(), weights.length / BYTES_PER_GB,
                stage.getKvGbPerGpu());
    }

    @PostMapping(ApiPaths.ModelManager.QUALITY_SAMPLES)
    public ResponseEntity<Void> qualitySample(@RequestBody QualitySample sample) {
        qualityGate.observe(sample);
        return ResponseEntity.accepted().build();
    }
}
