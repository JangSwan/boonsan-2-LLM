package com.llmserving.worker.config;

import com.llmserving.worker.scheduling.EngineEventListener;
import com.llmserving.worker.scheduling.EngineScheduler;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 빈 등록 보조: EngineEventListener 구현(CreditManager, LeaseRenewer)을 EngineScheduler에 구독시킨다.
 * EngineScheduler와 구독자 사이의 생성자 순환 의존을 피하려고 여기서 묶는다.
 */
@Component
@RequiredArgsConstructor
public class EngineListenerRegistrar implements SmartInitializingSingleton {

    private final EngineScheduler engineScheduler;
    private final List<EngineEventListener> listeners;

    @Override
    public void afterSingletonsInstantiated() {
        listeners.forEach(engineScheduler::subscribe);
    }
}
