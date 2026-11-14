package com.llmserving.modelmanager;

import lombok.Getter;

/**
 * 모델 축소 사다리의 한 단계 (EA LadderStage, entity, U1). 단계 추가는 자식 클래스 1개 추가로 한다.
 * 이름과 정밀도는 자식 클래스가 정하고, 가중치 위치와 KV 용량은 가중치 저장소가 준다.
 */
@Getter
public abstract class LadderStage {

    public static final String U0 = "U0";
    public static final String FP8 = "FP8";
    public static final String SMALL = "SMALL";

    private final String name;
    private final String precision;
    private final String weightUri;
    private final double kvGbPerGpu;

    protected LadderStage(String name, String precision, String weightUri, double kvGbPerGpu) {
        this.name = name;
        this.precision = precision;
        this.weightUri = weightUri;
        this.kvGbPerGpu = kvGbPerGpu;
    }

    public boolean isU0() {
        return U0.equals(name);
    }
}
