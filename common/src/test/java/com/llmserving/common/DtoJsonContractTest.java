package com.llmserving.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * common DTO가 앱 사이 JSON 계약(camelCase 필드 이름, 왕복 변환)을 지키는지 확인한다.
 */
class DtoJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streamChunk_boolean_필드는_isFirst_isLast_이름을_유지한다() throws Exception {
        StreamChunk chunk = new StreamChunk("req-1", 3, "안녕", true, false);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(chunk));

        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("requestId", "seq", "token", "isFirst", "isLast");
        assertThat(json.get("isFirst").asBoolean()).isTrue();
        assertThat(json.get("isLast").asBoolean()).isFalse();
    }

    @Test
    void streamChunk는_JSON_왕복_후_같은_값이다() throws Exception {
        StreamChunk chunk = new StreamChunk("req-1", 0, "첫", true, true);

        StreamChunk restored = mapper.readValue(mapper.writeValueAsString(chunk), StreamChunk.class);

        assertThat(restored).isEqualTo(chunk);
    }

    @Test
    void pullResponse는_lease와_작업_본문을_함께_왕복한다() throws Exception {
        Request request = new Request("req-1", "v1", 4000, 1_700_000_000_000L, "긴 입력");
        LeaseResponse lease = new LeaseResponse("lease-1", "req-1", "worker-1",
                1_700_000_030_000L, request, 1L);
        PullResponse response = new PullResponse("worker-1", List.of(lease), 1L, 3);

        PullResponse restored = mapper.readValue(mapper.writeValueAsString(response), PullResponse.class);

        assertThat(restored).isEqualTo(response);
        assertThat(restored.leases().get(0).request().prompt()).isEqualTo("긴 입력");
    }

    @Test
    void workerStatus는_JSON_왕복_후_같은_값이다() throws Exception {
        WorkerStatus status = new WorkerStatus("worker-spare", "v1", "PULLING", 120, true);

        WorkerStatus restored = mapper.readValue(mapper.writeValueAsString(status), WorkerStatus.class);

        assertThat(restored).isEqualTo(status);
        assertThat(mapper.readTree(mapper.writeValueAsString(status)).has("selfTestPassed")).isTrue();
    }

    @Test
    void errorResponse의_code는_enum_이름으로_직렬화된다() throws Exception {
        ErrorResponse error = new ErrorResponse(ErrorCode.STALE_EPOCH, "낡은 epoch", "req-1",
                "broker-primary", 1_700_000_000_000L);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(error));

        assertThat(json.get("code").asText()).isEqualTo("STALE_EPOCH");
        assertThat(mapper.treeToValue(json, ErrorResponse.class)).isEqualTo(error);
    }

    @Test
    void errorCode의_HTTP_상태는_CLAUDE_md_규칙을_따른다() {
        assertThat(ErrorCode.INVALID_INPUT.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.LEASE_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.LEASE_NOT_OWNED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.STALE_EPOCH.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.NOT_LEADER.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.REPLICA_NOT_PROMOTED.httpStatus()).isEqualTo(503);
    }

    @Test
    void apiPaths는_api_모듈명_자원_형식이다() {
        assertThat(ApiPaths.FrontGateway.QUESTIONS).isEqualTo("/api/front-gateway/questions");
        assertThat(ApiPaths.Broker.LEASE_RENEW).isEqualTo("/api/broker/leases/{leaseId}/renew");
        assertThat(ApiPaths.Broker.WORKER_RECLAIM).isEqualTo("/api/broker/workers/{workerId}/reclaim");
        assertThat(ApiPaths.Worker.STOP_PULL).isEqualTo("/api/worker/stop-pull");
        assertThat(ApiPaths.ModelManager.SWAP_STATUS).isEqualTo("/api/model-manager/swaps/status");
        assertThat(ApiPaths.FakeAws.INSTANCES).startsWith("/api/fake-aws/");
    }
}
