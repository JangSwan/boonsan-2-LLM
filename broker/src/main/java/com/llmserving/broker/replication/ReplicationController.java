package com.llmserving.broker.replication;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.ReplicationRequest;
import com.llmserving.common.ReplicationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * BrokerReplica의 REST 입구 (주 브로커 ReplicaPort → 복제본). 받아서 위임만 한다.
 */
@RestController
@RequiredArgsConstructor
public class ReplicationController {

    private final BrokerReplica brokerReplica;

    @PostMapping(ApiPaths.Broker.REPLICATIONS)
    public ReplicationResponse apply(@RequestBody ReplicationRequest request) {
        brokerReplica.apply(new ChangeRecord(request.seq(), request.kind(), request.payload(), request.epoch()));
        return new ReplicationResponse(request.seq(), brokerReplica.epoch(), true);
    }
}
