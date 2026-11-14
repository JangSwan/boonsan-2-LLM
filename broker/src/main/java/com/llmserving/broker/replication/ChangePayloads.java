package com.llmserving.broker.replication;

import com.llmserving.broker.lease.Lease;
import com.llmserving.broker.queue.QueueItem;
import com.llmserving.common.Request;
import java.util.Collection;
import java.util.List;

/**
 * ChangeRecord.payload에 담는 JSON 모양 (기술 보조: DTO 매핑). 상태가 바뀌는 entity를 그대로 직렬화하지 않고
 * 값 record로 옮긴다.
 */
public final class ChangePayloads {

    private ChangePayloads() {
    }

    public record ItemData(Request request, long enqueuedAt, int retryCount) {
        public static ItemData of(QueueItem item) {
            return new ItemData(item.getRequest(), item.getEnqueuedAt(), item.getRetryCount());
        }

        public QueueItem toItem() {
            return new QueueItem(request, enqueuedAt, retryCount);
        }
    }

    public record LeaseData(String leaseId, String workerId, long expiresAt, ItemData item) {
        public static LeaseData of(Lease lease) {
            return new LeaseData(lease.getLeaseId(), lease.getWorkerId(), lease.getExpiresAt(),
                    ItemData.of(lease.getItem()));
        }

        public Lease toLease() {
            return new Lease(leaseId, workerId, expiresAt, item.toItem());
        }
    }

    public record Leases(List<LeaseData> leases) {
        public static Leases of(Collection<Lease> leases) {
            return new Leases(leases.stream().map(LeaseData::of).toList());
        }
    }

    public record RequestIds(List<String> requestIds) {
    }

    public record LeaseRenewal(String leaseId, long expiresAt) {
    }

    public record LeaseIdData(String leaseId) {
    }

    public record Snapshot(List<ItemData> queue, List<ItemData> quarantined, List<LeaseData> leases) {
    }

    public record Empty() {
    }

    public record Entry(String kind, String payload) {
    }

    public record Batch(List<Entry> entries) {
    }
}
