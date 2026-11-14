package com.llmserving.broker.queue;

import com.llmserving.broker.replication.ChangePayloads;
import com.llmserving.broker.replication.ChangeRecord;
import com.llmserving.broker.replication.Replicator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 재시도 상한에 닿은 요청 격리 (EA QuarantineQueue, R3 보완). 격리된 요청은 더 이상 인출되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class QuarantineQueue {

    private final Replicator replicator;
    private final CopyOnWriteArrayList<QueueItem> items = new CopyOnWriteArrayList<>();

    public synchronized void isolate(QueueItem item) {
        if (contains(item.requestId())) {
            return;
        }
        replicator.commit(ChangeRecord.QUARANTINED, ChangePayloads.ItemData.of(item), () -> items.add(item));
    }

    public List<QueueItem> list() {
        return List.copyOf(items);
    }

    // ---- 복제본 쪽 적용 ----

    public synchronized void applyIsolated(QueueItem item) {
        if (!contains(item.requestId())) {
            items.add(item);
        }
    }

    public synchronized void restore(List<QueueItem> snapshot) {
        items.clear();
        items.addAll(snapshot);
    }

    private boolean contains(String requestId) {
        return items.stream().anyMatch(i -> i.requestId().equals(requestId));
    }
}
