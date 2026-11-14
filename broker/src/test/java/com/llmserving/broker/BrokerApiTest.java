package com.llmserving.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.llmserving.broker.adapter.PrimaryLockPort;
import com.llmserving.broker.replication.Replicator;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.EnqueueResponse;
import com.llmserving.common.ErrorCode;
import com.llmserving.common.ErrorResponse;
import com.llmserving.common.LeaseResponse;
import com.llmserving.common.PositionResponse;
import com.llmserving.common.PullRequest;
import com.llmserving.common.PullResponse;
import com.llmserving.common.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * HTTP 계약 확인: enqueue → 순번 → pull → complete (sd1). 복제본 없이 broker-primary 락으로 단독 커밋한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "llm.broker.expire-check-interval-ms=600000", "llm.broker.primary-lock-renew-interval-ms=1000"})
class BrokerApiTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private Replicator replicator;

    @MockitoBean
    private PrimaryLockPort primaryLockPort;

    @BeforeEach
    void lockAlwaysHeld() {
        when(primaryLockPort.renewOrAcquire(anyString(), anyLong())).thenReturn(true);
        replicator.maintain();
    }

    @Test
    void 접수한_요청을_순번으로_안내하고_워커가_pull해서_완료한다() {
        Request request = new Request("req-api-1", "v1", 1200, 0, "긴 질문");

        EnqueueResponse enqueued = http.postForObject(ApiPaths.Broker.REQUESTS, request, EnqueueResponse.class);
        assertThat(enqueued.position()).isEqualTo(1);
        assertThat(enqueued.epoch()).isEqualTo(1);

        PositionResponse position = http.getForObject(ApiPaths.Broker.REQUEST_POSITION, PositionResponse.class,
                "req-api-1");
        assertThat(position.position()).isEqualTo(1);

        PullResponse pulled = http.postForObject(ApiPaths.Broker.LEASES_PULL,
                new PullRequest("worker-1", "v1", 2), PullResponse.class);
        assertThat(pulled.leases()).hasSize(1);
        LeaseResponse lease = pulled.leases().get(0);
        assertThat(lease.request().prompt()).isEqualTo("긴 질문");

        LeaseResponse renewed = http.postForObject(ApiPaths.Broker.LEASE_RENEW, null, LeaseResponse.class,
                lease.leaseId());
        assertThat(renewed.expiresAt()).isGreaterThanOrEqualTo(lease.expiresAt());

        ResponseEntity<LeaseResponse> completed = http.postForEntity(ApiPaths.Broker.LEASE_COMPLETE, null,
                LeaseResponse.class, lease.leaseId());
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 없는_lease는_404_ErrorResponse로_응답한다() {
        ResponseEntity<ErrorResponse> response = http.postForEntity(ApiPaths.Broker.LEASE_COMPLETE, null,
                ErrorResponse.class, "no-such-lease");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.LEASE_NOT_FOUND);
        assertThat(response.getBody().instanceId()).isEqualTo("broker-primary");
    }

    @Test
    void 잘못된_입력은_400으로_응답한다() {
        ResponseEntity<ErrorResponse> response = http.postForEntity(ApiPaths.Broker.REQUESTS,
                new Request(null, "v1", 1, 0, "x"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
