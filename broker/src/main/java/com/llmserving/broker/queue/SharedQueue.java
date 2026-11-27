package com.llmserving.broker.queue;

import com.llmserving.broker.config.BrokerProperties;
import com.llmserving.broker.replication.ChangePayloads;
import com.llmserving.broker.replication.ChangeRecord;
import com.llmserving.broker.replication.Replicator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공용 대기열 (EA SharedQueue, C2). 버전 일치 작업만 인출한다.
 * 모든 변경은 Replicator가 복제본에 동기 복제한 뒤 커밋한다 (sd5 3~6번).
 * 스레드 안전: 변경은 이 객체의 모니터 안에서만 하고, 목록은 스냅샷이 잠금 없이 읽을 수 있게 COW로 둔다.
 * <p>
 * 유형별 대기열 인출 (llm.broker.typed-pull, 실측 후 보완, 비평 QAS1 제안 채택): 켜면 입력 토큰으로 짧은 요청 줄과
 * 긴 요청 줄을 나눠 본다. 저장은 지금처럼 목록 하나이고 줄은 입력 토큰으로 정해지므로, 복제·승격 후에도 두 줄이 그대로다.
 * 두 줄 모두 모든 워커가 꺼낸다(C2 공용 인출 유지). 꺼지면 지금까지와 같은 도착 순서 인출이다.
 */
@Slf4j
@Component
public class SharedQueue {

    private final Replicator replicator;
    private final Clock clock;
    private final BrokerProperties.TypedPull typedPull;
    private final CopyOnWriteArrayList<QueueItem> items = new CopyOnWriteArrayList<>();

    public SharedQueue(Replicator replicator, Clock clock, BrokerProperties properties) {
        this.replicator = replicator;
        this.clock = clock;
        this.typedPull = properties.typedPull();
    }

    /**
     * 꼬리에 적재하고 순번(1부터)을 돌려준다. 같은 요청 ID가 이미 있으면 다시 넣지 않고 현재 순번을 준다
     * (승격 후 Front Gateway의 접수 재연결, sd5 12번).
     */
    public synchronized int push(QueueItem item) {
        int existing = indexOf(item.requestId());
        if (existing >= 0) {
            return positionInLine(items, existing);
        }
        replicator.commit(ChangeRecord.QUEUE_PUSHED, ChangePayloads.ItemData.of(item), () -> items.add(item));
        int position = positionInLine(items, items.size() - 1);
        log.info("event=request.enqueued requestId={} versionTag={} inputTokens={} line={} position={} msg=공용 큐 적재",
                item.requestId(), item.versionTag(), item.getRequest().inputTokens(), lineOf(item), position);
        return position;
    }

    /** 유형별 인출을 쓰지 않을 때(또는 워커의 긴 요청 수를 모를 때)의 인출. */
    public List<QueueItem> pullMatching(String versionTag, int k) {
        return pullMatching(versionTag, k, 0);
    }

    /**
     * 버전 태그가 같은 작업을 최대 k건 꺼낸다. 확인과 제거를 한 번에 한다 (sd1 16번).
     * 재시도 중인 작업(lease 만료를 겪은 작업)은 한 번에 1건까지만 내준다: 엔진을 죽인 요청과 함께 만료된 작업들이
     * 다음 워커에 다시 함께 실려 같이 죽는 일을 막아, 재시도 상한에 닿는 것이 그 요청 1건이 되게 한다 (sd4, R3 단점 2).
     * <p>
     * 유형별 인출이 켜져 있으면 꺼내는 순서는 다음과 같다. 줄 안에서는 접수 순서를 지킨다.
     * <ol>
     *   <li>기한에 가까운 긴 요청 (대기 ≥ long-deadline-sec − long-deadline-margin-sec)</li>
     *   <li>짧은 요청</li>
     *   <li>나머지 긴 요청. 단 짧은 요청이 아직 남아 있으면, 이 워커가 가진 긴 요청 lease와 합쳐
     *       long-max-per-worker까지만 (짧은 요청이 들어갈 자리를 남긴다)</li>
     * </ol>
     *
     * @param workerLongActive 이 워커가 지금 가진 긴 요청 lease 수 (LeaseManager가 센다)
     */
    public synchronized List<QueueItem> pullMatching(String versionTag, int k, int workerLongActive) {
        if (k <= 0) {
            return List.of();
        }
        List<QueueItem> picked = typedPull.enabled()
                ? pickTyped(versionTag, k, workerLongActive)
                : pickInOrder(versionTag, k);
        if (picked.isEmpty()) {
            return List.of();
        }
        List<String> ids = picked.stream().map(QueueItem::requestId).toList();
        replicator.commit(ChangeRecord.QUEUE_REMOVED, new ChangePayloads.RequestIds(ids), () -> items.removeAll(picked));
        return picked;
    }

    /** 긴 요청 줄인가. 유형별 인출이 꺼져 있으면 모두 한 줄(짧은 요청 줄)로 본다. */
    public boolean isLong(QueueItem item) {
        return typedPull.enabled() && item.getRequest().inputTokens() >= typedPull.longThresholdTokens();
    }

    private List<QueueItem> pickInOrder(String versionTag, int k) {
        List<QueueItem> picked = new ArrayList<>();
        RetryGate gate = new RetryGate();
        for (QueueItem item : items) {
            if (picked.size() >= k) {
                break;
            }
            if (versionTag.equals(item.versionTag()) && gate.admit(item)) {
                picked.add(item);
            }
        }
        return picked;
    }

    private List<QueueItem> pickTyped(String versionTag, int k, int workerLongActive) {
        long now = clock.millis();
        List<QueueItem> dueLong = new ArrayList<>();
        List<QueueItem> shorts = new ArrayList<>();
        List<QueueItem> otherLong = new ArrayList<>();
        for (QueueItem item : items) {
            if (!versionTag.equals(item.versionTag())) {
                continue;
            }
            if (!isLong(item)) {
                shorts.add(item);
            } else if (now - item.getEnqueuedAt() >= typedPull.promoteAfterWaitMs()) {
                dueLong.add(item);
            } else {
                otherLong.add(item);
            }
        }
        List<QueueItem> picked = new ArrayList<>();
        RetryGate gate = new RetryGate();
        int longPicked = 0;
        for (QueueItem item : dueLong) {
            if (picked.size() >= k) {
                break;
            }
            if (gate.admit(item)) {
                picked.add(item);
                longPicked++;
                log.info("event=queue.long-deadline-promoted requestId={} waitedMs={} msg=기한이 가까운 긴 요청을 먼저 꺼냄",
                        item.requestId(), now - item.getEnqueuedAt());
            }
        }
        int shortsPicked = 0;
        for (QueueItem item : shorts) {
            if (picked.size() >= k) {
                break;
            }
            if (gate.admit(item)) {
                picked.add(item);
                shortsPicked++;
            }
        }
        boolean shortWaiting = shortsPicked < shorts.size();
        int longRoom = shortWaiting
                ? Math.max(0, typedPull.longMaxPerWorker() - workerLongActive - longPicked)
                : Integer.MAX_VALUE;
        for (QueueItem item : otherLong) {
            if (picked.size() >= k || longRoom <= 0) {
                break;
            }
            if (gate.admit(item)) {
                picked.add(item);
                longRoom--;
            }
        }
        return picked;
    }

    /** 한 번의 인출에서 재시도 중인 작업은 1건만 통과시킨다 (sd4). */
    private static final class RetryGate {
        private boolean retriedPicked;

        boolean admit(QueueItem item) {
            if (item.getRetryCount() > 0) {
                if (retriedPicked) {
                    return false;
                }
                retriedPicked = true;
            }
            return true;
        }
    }

    /** 다시 처리할 작업을 맨 앞에 넣는다 (이미 기다린 요청이므로 먼저 나간다). */
    public synchronized void requeue(QueueItem item) {
        if (indexOf(item.requestId()) >= 0) {
            return;
        }
        replicator.commit(ChangeRecord.QUEUE_REQUEUED, ChangePayloads.ItemData.of(item), () -> items.add(0, item));
    }

    /**
     * 대기 순번 (1부터). 큐에 없으면(이미 인출됨) 0.
     * 유형별 인출이 켜져 있으면 자기 줄 안의 순번이다: 긴 요청끼리는 순서가 바뀌지 않으므로 안내한 순번이 지켜진다.
     */
    public int positionOf(String requestId) {
        replicator.ensurePrimary();
        List<QueueItem> snapshot = List.copyOf(items);  // 잠금 없이 읽으므로 한 사본으로 센다
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).requestId().equals(requestId)) {
                return positionInLine(snapshot, i);
            }
        }
        return 0;
    }

    public int length() {
        return items.size();
    }

    /** 긴 요청 줄 길이 (유형별 인출이 꺼져 있으면 0). */
    public int longLength() {
        int count = 0;
        for (QueueItem item : items) {
            if (isLong(item)) {
                count++;
            }
        }
        return count;
    }

    /** 짧은 요청 줄 길이 (유형별 인출이 꺼져 있으면 전체 길이). */
    public int shortLength() {
        return items.size() - longLength();
    }

    public boolean isTypedPullEnabled() {
        return typedPull.enabled();
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

    private String lineOf(QueueItem item) {
        return isLong(item) ? "long" : "short";
    }

    /** 목록의 index번째 작업이 자기 줄에서 몇 번째인가 (1부터). 꺼져 있으면 전체 순번과 같다. */
    private int positionInLine(List<QueueItem> list, int index) {
        boolean targetLong = isLong(list.get(index));
        int position = 0;
        for (int i = 0; i <= index; i++) {
            if (isLong(list.get(i)) == targetLong) {
                position++;
            }
        }
        return position;
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
