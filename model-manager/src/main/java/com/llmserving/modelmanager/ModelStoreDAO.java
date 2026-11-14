package com.llmserving.modelmanager;

import com.llmserving.modelmanager.adapter.ModelStorePort;
import com.llmserving.modelmanager.config.ModelManagerProperties;
import com.llmserving.modelmanager.config.StageFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * 단계별 가중치 원본(S3) 접근 (EA ModelStoreDAO, boundary, real 프로필). ModelStorePort를 구현한다.
 * 실제 가중치 파일(수 GB)은 워커의 추론 엔진이 S3에서 직접 읽으므로, 여기서는 객체가 있는지 확인하고 목록(manifest)을 돌려준다.
 */
@Component
@Profile("real")
public class ModelStoreDAO implements ModelStorePort {

    private final S3Client s3;
    private final List<LadderStage> stages;

    public ModelStoreDAO(ModelManagerProperties properties) {
        this.s3 = S3Client.builder().region(Region.of(properties.store().region())).build();
        this.stages = properties.store().stages().stream().map(StageFactory::create).toList();
    }

    @Override
    public byte[] loadWeights(LadderStage stage) {
        URI uri = URI.create(stage.getWeightUri());
        HeadObjectResponse head = s3.headObject(b -> b.bucket(uri.getHost()).key(uri.getPath().substring(1)));
        return ("{\"stage\":\"" + stage.getName() + "\",\"bytes\":" + head.contentLength() + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<LadderStage> listStages() {
        return stages;
    }
}
