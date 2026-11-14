package com.llmserving.broker.adapter;

import com.llmserving.common.ReplicationRequest;
import com.llmserving.common.ReplicationResponse;

/**
 * 복제본 broker 동기 복제 포트 (Replicator → BrokerReplica.apply).
 * 복제본이 더 높은 epoch를 가지면 StaleEpochException, 응답이 없으면 ReplicaUnavailableException을 던진다.
 */
public interface ReplicaPort {

    ReplicationResponse send(ReplicationRequest request);
}
