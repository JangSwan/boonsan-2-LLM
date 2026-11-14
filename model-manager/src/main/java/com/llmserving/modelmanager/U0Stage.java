package com.llmserving.modelmanager;

/** 8B 원정밀. 롤백 목표 (EA U0Stage). */
public class U0Stage extends LadderStage {

    public U0Stage(String weightUri, double kvGbPerGpu) {
        super(U0, "BF16", weightUri, kvGbPerGpu);
    }
}
