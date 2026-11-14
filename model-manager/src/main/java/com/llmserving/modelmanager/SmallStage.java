package com.llmserving.modelmanager;

/** 같은 계열 3B 소형 모델 (EA SmallStage). */
public class SmallStage extends LadderStage {

    public SmallStage(String weightUri, double kvGbPerGpu) {
        super(SMALL, "BF16-3B", weightUri, kvGbPerGpu);
    }
}
