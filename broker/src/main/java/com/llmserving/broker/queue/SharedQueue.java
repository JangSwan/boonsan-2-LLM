package com.llmserving.broker.queue;

import com.llmserving.broker.replication.ChangePayloads;
import com.llmserving.broker.replication.ChangeRecord;
import com.llmserving.broker.replication.Replicator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공용 대기열 (EA SharedQueue, C2). 버전 일치 작업만 인출한다.
 * 모든 변경은 Replicator가 복제본에 동기 복제한 뒤 커밋한다 (sd5 3~6번).
 * 스레드 안전: 변경은 이 객체의 모니터 안에서만 하고, 목록은 스냅샷이 잠금 없이 읽을 수 있게 COW로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedQueue {

    private final Replicator replicator;
    private final CopyOnWriteArrayList<QueueItem> items = new CopyOnWriteArrayList<>();

    /**
     * 꼬리에 적재하고 순번(1부터)을 돌려준다. 같은 요청 ID가 이미 있으면 다시 넣지 않고 현재 순번을 준다
     * (승격 후 Front Gateway의 접수 재연결, sd5 12번).
     */
    public synchronized int push(QueueItem item) {
        int existing = indexOf(item.requestId());
        if (existing >= 0) {
            return existing + 1;
        }
        replicator.commit(ChangeRecord.QUEUE_PUSHED, ChangePayloads.ItemData.of(item), () -> items.add(item));
        log.info("event=request.enqueued requestId={} versionTag={} inputTokens={} position={} msg=공용 큐 적재",
                item.requestId(), item.versionTag(), item.getRequest().inputTokens(), items.size());
        return items.size();
    }

    /**
     * 버전 태그가 같은 작업을 앞에서부터 최대 k건 꺼낸다. 확인과 제거를 한 번에 한다.
     * 재시도 중인 작업(lease 만료를 겪은 작업)은 한 번에 1건까지만 내준다: 엔진을 죽인 요청과 함께 만료된 작업들이
     * 다음 워커에 다시 함께 실려 같이 죽는 일을 막아, 재시도 상한에 닿는 것이 그 요청 1건이 되게 한다 (sd4, R3 단점 2).
     */
    public synchronized List<QueueItem> pullMatching(String versionTag, int k) {
        if (k <= 0) {
            return List.of();
        }
        List<QueueItem> picked = new ArrayList<>();
        boolean retriedPicked = false;
        for (QueueItem item : items) {
            if (picked.size() >= k) {
                break;
            }
            if (!versionTag.equals(item.versionTag())) {
                continue;
            }
            if (item.getRetryCount() > 0) {
                if (retriedPicked) {
                    continue;
                }
                retriedPicked = true;
            }
            picked.add(item);
        }
        if (picked.isEmpty()) {
            return List.of();
        }
        List<String> ids = picked.stream().map(QueueItem::requestId).toList();
        replicator.commit(ChangeRecord.QUEUE_REMOVED, new ChangePayloads.RequestIds(ids), () -> items.removeAll(picked));
        return picked;
    }

    /** 다시 처리할 작업을 맨 앞에 넣는다 (이미 기다린 요청이므로 먼저 나간다). */
    public synchronized void requeue(QueueItem item) {
        if (indexOf(item.requestId()) >= 0) {
            return;
        }
        replicator.commit(ChangeRecord.QUEUE_REQUEUED, ChangePayloads.ItemData.of(item), () -> items.add(0, item));
    }

    /** 대기 순번 (1부터). 큐에 없으면(이미 인출됨) 0. */
    public int positionOf(String requestId) {
        replicator.ensurePrimary();
        return indexOf(requestId) + 1;
    }

    public int length() {
        return items.size();
    }

    /** 버전 태그가 같은 대기 작업 수 (sd6: 현재 버전의 마지막 워커가 남은 작업을 다 가져갔는지 확인). */
    public int countMatching(String versionTag) {
        int count = 0;
        for (QueueItem item : items) {
            if (versionTag.equals(item.versionTag())) {
                count++;
            }
        }
        return count;
    }

    public long currentEpoch() {
        return replicator.currentEpoch();
    }

    /** 스냅샷용 읽기 (잠금 없이 일관된 사본). */
    public List<QueueItem> items() {
        return List.copyOf(items);
    }

    // ---- 복제본 쪽 적용 (BrokerReplica.apply에서만 호출, 다시 복제하지 않는다) ----

    public synchronized void applyPushed(QueueItem item) {
        if (indexOf(item.requestId()) < 0) {
            items.add(item);
        }
    }

    public synchronized void applyRequeued(QueueItem item) {
        if (indexOf(item.requestId()) < 0) {
            items.add(0, item);
        }
    }

    public synchronized void applyRemoved(List<String> requestIds) {
        items.removeIf(item -> requestIds.contains(item.requestId()));
    }

    public synchronized void restore(List<QueueItem> snapshot) {
        items.clear();
        items.addAll(snapshot);
    }

    private int indexOf(String requestId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).requestId().equals(requestId)) {
                return i;
            }
        }
        return -1;
    }
}
