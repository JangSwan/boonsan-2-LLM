package com.llmserving.modelmanager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 품질 이상 시 U0로 되돌림 (EA RollbackManager, U1). 롤백도 ModelConfig를 거쳐 교체와 같은 경로로 한다.
 */
@Slf4j
@Component
public class RollbackManager {

    /** ModelConfig → QualityGate → RollbackManager → ModelConfig 순환을 늦은 조회로 푼다. */
    private final ObjectProvider<ModelConfig> modelConfig;

    public RollbackManager(ObjectProvider<ModelConfig> modelConfig) {
        this.modelConfig = modelConfig;
    }

    public void rollbackToU0() {
        ModelConfig config = modelConfig.getObject();
        if (config.isOnU0()) {
            log.info("event=model.rollback-skipped msg=이미 U0로 운영 중");
            return;
        }
        config.switchTo(config.stageOf(LadderStage.U0));
        log.warn("event=model.rolled-back msg=품질 이상으로 U0 롤백 시작 (교체와 같은 롤링 절차)");
    }
}
