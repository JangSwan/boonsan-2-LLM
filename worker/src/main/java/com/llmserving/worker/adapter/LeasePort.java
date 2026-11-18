package com.llmserving.worker.adapter;

import com.llmserving.common.LeaseResponse;
import java.util.List;

/**
 * broker LeaseManager 호출 포트 (CreditManager: pull, complete / LeaseRenewer: renew).
 * 구현은 pull로 받은 lease를 요청 ID로 기억해, 갱신·완료를 요청 ID로 할 수 있게 한다.
 * 주 브로커 호출이 실패하면 복제본 주소로 전환하고, 낮은 epoch 응답은 거부한다 (sd5 10·12번).
 */
public interface LeasePort {

    List<LeaseResponse> pull(String workerId, String versionTag, int n);

    /**
     * 공용 큐에 남은 같은 버전 작업 수. 인출 없이(n = 0 pull) 확인한다 (sd6 마지막 워커의 드레인 종료 판단).
     * broker가 응답하지 않으면 UpstreamUnavailableException.
     */
    int remainingFor(String workerId, String versionTag);

    /** lease가 이미 없으면(회수, 만료) LeaseGoneException. */
    void renew(String requestId);

    /** 완료 ack. lease가 이미 없으면 조용히 끝낸다 (멱등). */
    void complete(String requestId);
}
