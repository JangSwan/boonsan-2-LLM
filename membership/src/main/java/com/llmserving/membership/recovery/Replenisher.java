package com.llmserving.membership.recovery;

import com.llmserving.membership.adapter.SpotInstancePort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 대체 서버 요청과 합류 처리 (EA Replenisher, R3. R0와 같은 신규 확보 경로).
 * 대체 서버 요청에는 workerId 기반 중복 방지 키를 넣어, 락 만료 순간 두 제어기가 함께 요청해도 두 번째는 무시된다.
 * 기동 완료 통지는 두 제어기가 모두 받지만, 요청을 보낸 리더만 대기 중인 대체 서버를 알고 있으므로 리더만 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Replenisher {

    private final SpotInstancePort spotInstancePort;
    private final RecoveryNotifier recoveryNotifier;
    /** 대체 인스턴스 ID → 장애가 났던 워커 ID. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();

    /** 재보충 지시 (sd3 7~8번, T재보충 시작). */
    public void requestReplacement(String workerId) {
        if (pending.containsValue(workerId)) {
            return;
        }
        String idempotencyKey = "replace-" + workerId;
        try {
            String instanceId = spotInstancePort.requestInstance(idempotencyKey);
            pending.put(instanceId, workerId);
            log.info("event=replenish.requested workerId={} instanceId={} idempotencyKey={} msg=대체 서버 요청",
                    workerId, instanceId, idempotencyKey);
        } catch (RuntimeException e) {
            log.error("event=replenish.failed workerId={} msg=대체 서버 요청 실패: {}", workerId, e.getMessage());
        }
    }

    /** 인스턴스 기동 완료 (sd3 17번). 이후 새 워커가 적재·시험 후 하트비트로 합류를 알린다. */
    public void onInstanceReady(String instanceId) {
        if (!pending.containsKey(instanceId)) {
            log.debug("event=replenish.ignored instanceId={} msg=이 제어기가 요청한 대체 서버가 아님 (리더가 아님)", instanceId);
            return;
        }
        log.info("event=replenish.instance-ready instanceId={} replaces={} msg=대체 서버 기동 완료, 적재·시험 대기",
                instanceId, pending.get(instanceId));
    }

    /** 합류 확인 (sd3 20~21번). */
    public void onJoinConfirmed(String workerId) {
        String replaced = pending.remove(workerId);
        if (replaced == null) {
            return;
        }
        log.info("event=replenish.joined instanceId={} replaces={} msg=대체 서버 합류 완료", workerId, replaced);
        recoveryNotifier.onJoined(replaced);
    }

    public boolean isPending(String instanceId) {
        return pending.containsKey(instanceId);
    }
}
