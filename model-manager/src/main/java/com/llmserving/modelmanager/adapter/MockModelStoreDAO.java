package com.llmserving.modelmanager.adapter;

import com.llmserving.modelmanager.LadderStage;
import com.llmserving.modelmanager.config.ModelManagerProperties;
import com.llmserving.modelmanager.config.StageFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * mock 프로필의 가중치 저장소. 단계별 가짜 가중치 정보(위치, KV 용량)를 주고, 적재 지연을 흉내 낸다.
 * real(ModelStoreDAO)과 같은 계약: 없는 단계는 IllegalArgumentException.
 */
@Slf4j
@Component
@Profile("mock")
public class MockModelStoreDAO implements ModelStorePort {

    private final List<LadderStage> stages;
    private final long loadDelayMs;

    public MockModelStoreDAO(ModelManagerProperties properties) {
        this.stages = properties.store().stages().stream().map(StageFactory::create).toList();
        this.loadDelayMs = properties.store().loadDelayMs();
    }

    @Override
    public byte[] loadWeights(LadderStage stage) {
        if (stages.stream().noneMatch(s -> s.getName().equals(stage.getName()))) {
            throw new IllegalArgumentException("가중치가 없는 단계: " + stage.getName());
        }
        try {
            Thread.sleep(loadDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ("{\"stage\":\"" + stage.getName() + "\",\"weightUri\":\"" + stage.getWeightUri() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<LadderStage> listStages() {
        return stages;
    }
}
