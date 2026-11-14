package com.llmserving.membership.detection;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.WorkerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HealthDetector의 REST 입구 (Worker HeartbeatPort). 위임만 한다. */
@RestController
@RequiredArgsConstructor
public class HeartbeatController {

    private final HealthDetector healthDetector;

    @PostMapping(ApiPaths.Membership.HEARTBEATS)
    public ResponseEntity<Void> heartbeat(@RequestBody WorkerStatus status) {
        if (status.workerId() == null || status.workerId().isBlank()) {
            throw new IllegalArgumentException("workerId가 필요합니다");
        }
        healthDetector.onHeartbeat(status);
        return ResponseEntity.accepted().build();
    }
}
