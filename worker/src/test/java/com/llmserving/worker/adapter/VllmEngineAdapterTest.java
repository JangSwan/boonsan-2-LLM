package com.llmserving.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.WorkerTestSupport;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 가짜 vLLM 서버로 real 어댑터를 확인한다: 요청 모양과 토큰 수집, 그리고 실패 구분
 * (요청 하나의 거절은 그 요청만, 첫 토큰 전 연결 끊김은 다시 연결, 엔진 무응답·5xx만 엔진 장애).
 */
class VllmEngineAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger flakyDrops = new AtomicInteger();
    private VllmEngineAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        WorkerProperties p = WorkerTestSupport.properties();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        WorkerProperties props = new WorkerProperties(p.instanceId(), false, 8, 1, 1, 1, 1, 1, 1, p.brokerUrls(),
                p.membershipUrls(), "", "", 1, p.budget(), p.credit(),
                new WorkerProperties.Engine(5, 0.02, 1.0, 5, 1, 1, 4, "", 0, 0, url, "test-model", 16, 2));
        adapter = new VllmEngineAdapter(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 본문의 표시로 동작을 고른다: TOO_LONG → 400, ENGINE_ERROR → 500, FLAKY → 처음 두 번 응답 없이 끊음, DOWN → 늘 끊음. */
    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody.set(body);
        if (body.contains("DOWN") || (body.contains("FLAKY") && flakyDrops.getAndIncrement() < 2)) {
            exchange.close();  // 헤더 없이 연결을 닫는다 (vLLM이 keep-alive 연결을 먼저 닫은 경우와 같음)
            return;
        }
        if (body.contains("TOO_LONG") || body.contains("ENGINE_ERROR")) {
            byte[] error = "{\"object\":\"error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.contains("TOO_LONG") ? 400 : 500, error.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(error);
            }
            return;
        }
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
    }

    @Test
    void 질문을_채팅_메시지로_보내고_스트림_토큰을_순서대로_돌려준다() {
        Collected c = runUntilSettled(Map.of("r1", "질문"));

        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(lastBody.get()).contains("\"role\":\"user\"").contains("\"content\":\"질문\"")
                .contains("\"model\":\"test-model\"");
        assertThat(c.chunks).extracting(StreamChunk::token).containsExactly("안녕", "하세요", "");
        assertThat(c.chunks.get(0).isFirst()).isTrue();
        assertThat(c.chunks.get(c.chunks.size() - 1).isLast()).isTrue();
        assertThat(c.prefillDone).containsExactly("r1");
        assertThat(c.finished).containsExactly("r1");
        assertThat(c.failed).isEmpty();
    }

    @Test
    void 엔진이_한_요청만_거절하면_그_요청만_실패하고_다른_요청은_끝까지_생성된다() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("long", "TOO_LONG 아주 긴 입력");
        prompts.put("ok", "짧은 질문");

        Collected c = runUntilSettled(prompts);

        assertThat(c.failed).containsExactly("long");
        assertThat(c.finished).containsExactly("ok");
        assertThat(c.chunks).filteredOn(ch -> ch.requestId().equals("ok")).extracting(StreamChunk::token)
                .containsExactly("안녕", "하세요", "");
        assertThat(c.chunks).noneMatch(ch -> ch.requestId().equals("long"));
    }

    @Test
    void 첫_토큰_전_연결이_끊기면_그_요청만_다시_연결해_답을_받는다() {
        Collected c = runUntilSettled(Map.of("r1", "FLAKY 질문"));

        assertThat(flakyDrops.get()).isGreaterThanOrEqualTo(3);
        assertThat(c.failed).isEmpty();
        assertThat(c.finished).containsExactly("r1");
        assertThat(c.chunks).extracting(StreamChunk::token).containsExactly("안녕", "하세요", "");
    }

    @Test
    void 다시_연결해도_응답이_없으면_엔진_장애로_본다() {
        assertThatThrownBy(() -> {
            adapter.execute(new StepPlan(List.of(), Map.of("r1", 10), 256, Map.of("r1", "DOWN")));
            for (int i = 0; i < 200; i++) {
                adapter.execute(new StepPlan(List.of("r1"), Map.of(), 256, Map.of()));
            }
        }).isInstanceOf(EngineFailureException.class);
    }

    @Test
    void 엔진이_5xx를_돌려주면_엔진_장애로_본다() {
        assertThatThrownBy(() -> {
            adapter.execute(new StepPlan(List.of(), Map.of("r1", 10), 256, Map.of("r1", "ENGINE_ERROR")));
            for (int i = 0; i < 200; i++) {
                adapter.execute(new StepPlan(List.of("r1"), Map.of(), 256, Map.of()));
            }
        }).isInstanceOf(EngineFailureException.class);
    }

    @Test
    void 생성_시험은_채팅_엔드포인트가_200이면_통과한다() {
        assertThat(adapter.generationTest()).isTrue();
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
    }

    /** 모든 요청이 끝나거나 실패할 때까지 스텝을 돌린다. */
    private Collected runUntilSettled(Map<String, String> prompts) {
        Collected c = new Collected();
        Map<String, Integer> chunks = new LinkedHashMap<>();
        prompts.keySet().forEach(id -> chunks.put(id, 10));
        c.add(adapter.execute(new StepPlan(List.of(), chunks, 256, prompts)));
        for (int i = 0; i < 200 && c.finished.size() + c.failed.size() < prompts.size(); i++) {
            List<String> active = prompts.keySet().stream()
                    .filter(id -> !c.finished.contains(id) && !c.failed.contains(id)).toList();
            c.add(adapter.execute(new StepPlan(active, Map.of(), 256, Map.of())));
        }
        return c;
    }

    private static final class Collected {
        final List<StreamChunk> chunks = new ArrayList<>();
        final List<String> prefillDone = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        void add(StepResult r) {
            chunks.addAll(r.chunks());
            prefillDone.addAll(r.prefillDoneIds());
            finished.addAll(r.finishedIds());
            failed.addAll(r.failedIds());
        }
    }
}
