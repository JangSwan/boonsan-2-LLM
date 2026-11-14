package com.llmserving.membership.detection;

import com.llmserving.membership.adapter.WorkerControlPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 스팟 회수 통지 수신, 인출 중지 (EA NoticeHandler, R3). 인출 중지는 멱등이라 두 제어기가 함께 보내도 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeHandler {

    private final WorkerControlPort workerControlPort;
    private final HealthDetector healthDetector;

    /** sd3 1~4번. 워커는 새 pull만 멈추고 진행 중인 답변은 마저 생성한다. */
    public void onReclaimNotice(String workerId) {
        log.warn("event=notice.reclaim workerId={} msg=스팟 회수 통지 수신", workerId);
        workerControlPort.stopPull(workerId);
        healthDetector.onReclaimScheduled(workerId);
    }
}
