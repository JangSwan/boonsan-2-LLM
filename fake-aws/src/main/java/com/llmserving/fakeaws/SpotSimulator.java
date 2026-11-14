package com.llmserving.fakeaws;

import com.llmserving.common.ErrorCode;
import com.llmserving.fakeaws.adapter.MembershipNoticePort;
import com.llmserving.fakeaws.adapter.WorkerBootPort;
import com.llmserving.fakeaws.config.ApiException;
import com.llmserving.fakeaws.config.FakeAwsProperties;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AWS 스팟 흉내 (mock 모드 전용, EA 밖의 시연 부품). CLAUDE.md 2절 "가짜 AWS"의 세 가지 일만 한다.
 * <ol>
 *   <li>시연 명령을 받으면 Membership에 스팟 회수 통지를 보낸다.</li>
 *   <li>인스턴스 재생성 요청을 받으면 예비 워커에 기동 신호를 보낸다 (같은 중복 방지 키는 한 번만).</li>
 *   <li>기동 신호를 보낸 뒤 Membership에 인스턴스 기동 완료를 보낸다.</li>
 * </ol>
 */
@Slf4j
@Component
public class SpotSimulator {

    private final MembershipNoticePort membershipNoticePort;
    private final WorkerBootPort workerBootPort;
    private final Clock clock;
    private final FakeAwsProperties properties;
    private final List<String> spares;
    private final Map<String, Assignment> byKey = new HashMap<>();
    private final ExecutorService bootExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-aws-boot");
        t.setDaemon(true);
        return t;
    });

    public SpotSimulator(MembershipNoticePort membershipNoticePort, WorkerBootPort workerBootPort, Clock clock,
                         FakeAwsProperties properties) {
        this.membershipNoticePort = membershipNoticePort;
        this.workerBootPort = workerBootPort;
        this.clock = clock;
        this.properties = properties;
        this.spares = new ArrayList<>(properties.spareInstanceUrls());
    }

    /** 시연 명령: 스팟 회수 통지 (sd3 1번, 실제로는 2분 전 통지). */
    public void reclaim(String workerId) {
        log.warn("event=aws.spot-reclaim workerId={} msg=스팟 회수 통지 발송", workerId);
        membershipNoticePort.reclaimNotice(workerId);
    }

    /** 대체 인스턴스 요청 (sd3 8번). 같은 키를 창 안에서 다시 받으면 처음 배정한 인스턴스를 돌려준다. */
    public synchronized String requestInstance(String idempotencyKey) {
        long now = clock.millis();
        Assignment existing = byKey.get(idempotencyKey);
        if (existing != null && now - existing.at < properties.idempotencyWindowSec() * 1000L) {
            log.info("event=aws.instance-duplicate idempotencyKey={} instanceId={} msg=중복 요청 무시",
                    idempotencyKey, existing.instanceId);
            return existing.instanceId;
        }
        if (spares.isEmpty()) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "여유 예비 인스턴스가 없습니다");
        }
        String entry = spares.remove(0);
        int eq = entry.indexOf('=');
        String url = (eq > 0 ? entry.substring(eq + 1) : entry).trim();
        String instanceId = eq > 0 ? entry.substring(0, eq).trim() : URI.create(url).getHost();
        byKey.put(idempotencyKey, new Assignment(instanceId, now));
        log.info("event=aws.instance-requested idempotencyKey={} instanceId={} msg=대체 인스턴스 확보 시작",
                idempotencyKey, instanceId);
        bootExecutor.submit(() -> boot(url, instanceId));
        return instanceId;
    }

    private void boot(String url, String instanceId) {
        sleep(properties.bootDelayMs());
        try {
            workerBootPort.boot(url);
            log.info("event=aws.instance-booted instanceId={} msg=기동 신호 전송", instanceId);
        } catch (RuntimeException e) {
            log.error("event=aws.boot-failed instanceId={} msg=기동 신호 실패: {}", instanceId, e.getMessage());
            return;
        }
        membershipNoticePort.instanceReady(instanceId);
        log.info("event=aws.instance-ready instanceId={} msg=인스턴스 기동 완료 통지", instanceId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Assignment(String instanceId, long at) {
    }
}
