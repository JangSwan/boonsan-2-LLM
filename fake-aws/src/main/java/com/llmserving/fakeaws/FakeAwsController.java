package com.llmserving.fakeaws;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.InstanceRequest;
import com.llmserving.common.InstanceResponse;
import com.llmserving.common.SpotReclaimRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** fake-aws REST 입구: 대체 인스턴스 요청(SpotInstancePort), 시연용 스팟 회수 명령. 위임만 한다. */
@RestController
@RequiredArgsConstructor
public class FakeAwsController {

    private final SpotSimulator spotSimulator;

    @PostMapping(ApiPaths.FakeAws.INSTANCES)
    public InstanceResponse requestInstance(@RequestBody InstanceRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey가 필요합니다");
        }
        return new InstanceResponse(spotSimulator.requestInstance(request.idempotencyKey()), request.idempotencyKey());
    }

    @PostMapping(ApiPaths.FakeAws.SPOT_RECLAIMS)
    public ResponseEntity<Void> reclaim(@RequestBody SpotReclaimRequest request) {
        if (request.workerId() == null || request.workerId().isBlank()) {
            throw new IllegalArgumentException("workerId가 필요합니다");
        }
        spotSimulator.reclaim(request.workerId());
        return ResponseEntity.accepted().build();
    }
}
