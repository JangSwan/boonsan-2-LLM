package com.llmserving.frontgateway;

import com.llmserving.common.RatioResponse;
import com.llmserving.common.Request;
import com.llmserving.frontgateway.adapter.ModelConfigPort;
import java.util.random.RandomGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * v2 비율대로 새 요청에 v1/v2 태그 (EA VersionTagger). 비율 = v2 준비 워커 수 / 인출 중인 워커 수이므로
 * 버전별 처리 능력에 맞춰 나눠 보낸다 (sd6 13~14번).
 */
@Component
@RequiredArgsConstructor
public class VersionTagger {

    private final ModelConfigPort modelConfigClient;
    private final RandomGenerator versionRandom;

    public String tag(Request request) {
        RatioResponse ratio = modelConfigClient.currentRatio();
        if (ratio.targetVersion() == null || ratio.targetVersion().equals(ratio.activeVersion())) {
            return ratio.activeVersion();
        }
        return versionRandom.nextDouble() < modelConfigClient.getRatio() ? ratio.targetVersion() : ratio.activeVersion();
    }
}
