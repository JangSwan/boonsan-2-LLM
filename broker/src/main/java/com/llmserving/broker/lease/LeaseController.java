package com.llmserving.broker.lease;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.LeaseResponse;
import com.llmserving.common.PullRequest;
import com.llmserving.common.PullResponse;
import com.llmserving.common.ReclaimResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * LeaseManager의 REST 입구 (Worker LeasePort, Membership LeasePort). 요청을 받아 위임만 한다.
 */
@RestController
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseManager leaseManager;

    @PostMapping(ApiPaths.Broker.LEASES_PULL)
    public PullResponse pull(@RequestBody PullRequest request) {
        if (request.workerId() == null || request.versionTag() == null) {
            throw new IllegalArgumentException("workerId와 versionTag가 필요합니다");
        }
        List<Lease> leases = leaseManager.pull(request.workerId(), request.versionTag(), request.n());
        long epoch = leaseManager.currentEpoch();
        return new PullResponse(request.workerId(),
                leases.stream().map(l -> toResponse(l, true, epoch)).toList(), epoch,
                leaseManager.remainingFor(request.versionTag()));
    }

    @PostMapping(ApiPaths.Broker.LEASE_RENEW)
    public LeaseResponse renew(@PathVariable String leaseId) {
        return toResponse(leaseManager.renew(leaseId), false, leaseManager.currentEpoch());
    }

    @PostMapping(ApiPaths.Broker.LEASE_COMPLETE)
    public LeaseResponse complete(@PathVariable String leaseId) {
        return toResponse(leaseManager.complete(leaseId), false, leaseManager.currentEpoch());
    }

    @PostMapping(ApiPaths.Broker.WORKER_RECLAIM)
    public ReclaimResponse reclaim(@PathVariable String workerId) {
        int count = leaseManager.reclaim(workerId);
        return new ReclaimResponse(workerId, count, leaseManager.currentEpoch());
    }

    private static LeaseResponse toResponse(Lease lease, boolean withRequest, long epoch) {
        return new LeaseResponse(lease.getLeaseId(), lease.getRequestId(), lease.getWorkerId(),
                lease.getExpiresAt(), withRequest ? lease.getItem().getRequest() : null, epoch);
    }
}
