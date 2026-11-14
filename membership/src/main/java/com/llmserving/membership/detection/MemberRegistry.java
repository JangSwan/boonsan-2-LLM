package com.llmserving.membership.detection;

import com.llmserving.common.WorkerStatus;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 워커 목록 (EA MemberRegistry). 메모리에 두고 하트비트로 다시 채우므로 별도 데이터 복제가 필요 없다.
 */
@Component
@RequiredArgsConstructor
public class MemberRegistry {

    private final Clock clock;
    private final Map<String, Member> members = new ConcurrentHashMap<>();

    public void upsert(WorkerStatus status) {
        members.computeIfAbsent(status.workerId(), Member::new)
                .heartbeat(status.versionTag(), status.selfTestPassed(), clock.millis());
    }

    public void exclude(String workerId) {
        Member member = members.get(workerId);
        if (member != null) {
            member.exclude();
        }
    }

    public void scheduleReclaim(String workerId) {
        members.computeIfAbsent(workerId, id -> {
            Member m = new Member(id);
            m.heartbeat(null, false, clock.millis());
            return m;
        }).scheduleReclaim();
    }

    /** 제외되지 않은 워커 수. */
    public int activeCount() {
        return (int) members.values().stream().filter(m -> !m.isExcluded()).count();
    }

    public Optional<Member> find(String workerId) {
        return Optional.ofNullable(members.get(workerId));
    }

    public List<Member> overdue(long timeoutMs) {
        long now = clock.millis();
        return members.values().stream().filter(m -> m.isOverdue(now, timeoutMs)).toList();
    }

    public List<Member> list() {
        return List.copyOf(members.values());
    }
}
