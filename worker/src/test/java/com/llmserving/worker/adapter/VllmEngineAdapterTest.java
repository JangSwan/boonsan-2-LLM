package com.llmserving.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.WorkerTestSupport;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 가짜 vLLM 서버(채팅 스트림 응답)로 real 어댑터의 요청 모양과 토큰 수집을 확인한다. */
class VllmEngineAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private VllmEngineAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            boolean stream = body.contains("\"stream\":true");
            String response = stream
                    ? "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"안녕\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"하세요\"}}]}\n\n"
                      + "data: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"네\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        WorkerProperties p = WorkerTestSupport.properties();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        WorkerProperties props = new WorkerProperties(p.instanceId(), false, 8, 1, 1, 1, 1, 1, 1, p.brokerUrls(),
                p.membershipUrls(), "", "", 1, p.budget(), p.credit(),
                new WorkerProperties.Engine(5, 0.02, 1.0, 5, 1, 1, 4, "", 0, 0, url, "test-model", 16));
        adapter = new VllmEngineAdapter(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 질문을_채팅_메시지로_보내고_스트림_토큰을_순서대로_돌려준다() throws Exception {
        List<StreamChunk> chunks = new ArrayList<>();
        List<String> prefillDone = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        StepResult first = adapter.execute(new StepPlan(List.of(), Map.of("r1", 10), 256, Map.of("r1", "질문")));
        collect(first, chunks, prefillDone, finished);
        for (int i = 0; i < 100 && finished.isEmpty(); i++) {
            collect(adapter.execute(new StepPlan(List.of("r1"), Map.of(), 256, Map.of())), chunks, prefillDone,
                    finished);
        }

        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(lastBody.get()).contains("\"role\":\"user\"").contains("\"content\":\"질문\"")
                .contains("\"model\":\"test-model\"");
        assertThat(chunks).extracting(StreamChunk::token).containsExactly("안녕", "하세요", "");
        assertThat(chunks.get(0).isFirst()).isTrue();
        assertThat(chunks.get(chunks.size() - 1).isLast()).isTrue();
        assertThat(prefillDone).containsExactly("r1");
        assertThat(finished).containsExactly("r1");
    }

    @Test
    void 생성_시험은_채팅_엔드포인트가_200이면_통과한다() {
        assertThat(adapter.generationTest()).isTrue();
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
    }

    private static void collect(StepResult r, List<StreamChunk> chunks, List<String> prefillDone,
                                List<String> finished) {
        chunks.addAll(r.chunks());
        prefillDone.addAll(r.prefillDoneIds());
        finished.addAll(r.finishedIds());
    }
}
