package com.llmserving.modelmanager;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.SwapRequest;
import com.llmserving.common.SwapStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 교체 요청 진입점 (EA ModelSwapController). 설정 1곳(model-config)만 수정한다 (sd6 2번).
 */
@RestController
@RequiredArgsConstructor
public class ModelSwapController {

    private final ModelConfig modelConfig;

    /** 새 모델, 사다리 단계 지정. 게이트를 통과한 단계만 목표가 된다. */
    public void requestSwap(String model, String stage) {
        LadderStage ladderStage = modelConfig.stageOf(stage);
        String modelName = model == null || model.isBlank() ? modelConfig.activeModel() : model;
        modelConfig.setTarget(new ModelVersion(modelName, null, ladderStage));
    }

    public String getSwapStatus() {
        return modelConfig.getStatus();
    }

    @PostMapping(ApiPaths.ModelManager.SWAPS)
    public ResponseEntity<SwapStatusResponse> postSwap(@RequestBody SwapRequest request) {
        if (request.stage() == null || request.stage().isBlank()) {
            throw new IllegalArgumentException("stage가 필요합니다");
        }
        requestSwap(request.model(), request.stage());
        return ResponseEntity.accepted().body(modelConfig.statusView());
    }

    @GetMapping(ApiPaths.ModelManager.SWAP_STATUS)
    public SwapStatusResponse swapStatus() {
        return modelConfig.statusView();
    }
}
