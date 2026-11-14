package com.llmserving.worker.adapter;

import com.llmserving.common.StreamChunk;

/**
 * 토큰 푸시 포트 (ModelRuntime → front-gateway StreamController → StreamRelay). 구현: runtime.StreamPublisher (EA).
 */
public interface StreamPort {

    void publish(StreamChunk chunk);
}
