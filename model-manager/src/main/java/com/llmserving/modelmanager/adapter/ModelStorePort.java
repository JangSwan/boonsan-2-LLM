package com.llmserving.modelmanager.adapter;

import com.llmserving.modelmanager.LadderStage;
import java.util.List;

/**
 * 단계별 가중치 원본 접근 포트. real: modelmanager.ModelStoreDAO (EA, S3), mock: MockModelStoreDAO.
 */
public interface ModelStorePort {

    byte[] loadWeights(LadderStage stage);

    List<LadderStage> listStages();
}
