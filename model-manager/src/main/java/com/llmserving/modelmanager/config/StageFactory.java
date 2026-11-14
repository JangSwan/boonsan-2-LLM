package com.llmserving.modelmanager.config;

import com.llmserving.modelmanager.Fp8Stage;
import com.llmserving.modelmanager.LadderStage;
import com.llmserving.modelmanager.SmallStage;
import com.llmserving.modelmanager.U0Stage;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 기술 보조: 설정의 단계 이름을 LadderStage 자식 클래스로 바꾼다. 단계를 추가하면 자식 클래스와 여기 한 줄을 추가한다.
 */
public final class StageFactory {

    private static final Map<String, BiFunction<String, Double, LadderStage>> CONSTRUCTORS = Map.of(
            LadderStage.U0, U0Stage::new,
            LadderStage.FP8, Fp8Stage::new,
            LadderStage.SMALL, SmallStage::new);

    private StageFactory() {
    }

    public static LadderStage create(ModelManagerProperties.StageConfig config) {
        BiFunction<String, Double, LadderStage> constructor = CONSTRUCTORS.get(config.name());
        if (constructor == null) {
            throw new IllegalArgumentException("알 수 없는 사다리 단계: " + config.name());
        }
        return constructor.apply(config.weightUri(), config.kvGbPerGpu());
    }
}
