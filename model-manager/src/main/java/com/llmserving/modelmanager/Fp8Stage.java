package com.llmserving.modelmanager;

/** 같은 8B를 FP8(W8A8)로 (EA Fp8Stage). */
public class Fp8Stage extends LadderStage {

    public Fp8Stage(String weightUri, double kvGbPerGpu) {
        super(FP8, "FP8-W8A8", weightUri, kvGbPerGpu);
    }
}
