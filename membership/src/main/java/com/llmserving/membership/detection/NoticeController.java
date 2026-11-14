package com.llmserving.membership.detection;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.InstanceReadyRequest;
import com.llmserving.common.ReclaimNoticeRequest;
import com.llmserving.membership.recovery.Replenisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AWS(fake-aws) 통지의 REST 입구: 스팟 회수 통지 → NoticeHandler, 인스턴스 기동 완료 → Replenisher. 위임만 한다.
 */
@RestController
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeHandler noticeHandler;
    private final Replenisher replenisher;

    @PostMapping(ApiPaths.Membership.NOTICES)
    public ResponseEntity<Void> reclaimNotice(@RequestBody ReclaimNoticeRequest request) {
        if (request.workerId() == null || request.workerId().isBlank()) {
            throw new IllegalArgumentException("workerId가 필요합니다");
        }
        noticeHandler.onReclaimNotice(request.workerId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping(ApiPaths.Membership.INSTANCES_READY)
    public ResponseEntity<Void> instanceReady(@RequestBody InstanceReadyRequest request) {
        if (request.instanceId() == null || request.instanceId().isBlank()) {
            throw new IllegalArgumentException("instanceId가 필요합니다");
        }
        replenisher.onInstanceReady(request.instanceId());
        return ResponseEntity.accepted().build();
    }
}
