package com.llmserving.worker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.StageResponse;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * real 프로필의 추론 엔진: vLLM(OpenAI 호환 /v1/completions, stream: true).
 * plan.prompts에 처음 나온 요청은 그 본문으로 스트림을 열고, 이후 execute()가 불릴 때마다 지난 호출 이후 받은 토큰을
 * 같은 StepResult 모양으로 돌려준다. 첫 토큰이 온 요청은 prefillDoneIds, 스트림이 끝난 요청은 finishedIds에 넣는다.
 * 스텝 구성은 vLLM의 chunked prefill이 하고, B는 vLLM 시작 설정 max_num_batched_tokens로 넣는다 (CLAUDE.md 2절).
 * 사다리 단계 전환(loadWeights)은 vLLM을 해당 가중치로 다시 띄워야 하므로 여기서는 기록만 한다.
 */
@Slf4j
@Component
@Profile("real")
public class VllmEngineAdapter implements InferenceEnginePort {

    private final WorkerProperties.Engine config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    public VllmEngineAdapter(WorkerProperties properties, ObjectMapper objectMapper) {
        this.config = properties.engine();
        this.objectMapper = objectMapper;
    }

    @Override
    public StepResult execute(StepPlan plan) {
        plan.prompts().forEach(this::open);
        sleep(config.stepBaseMs());
        List<StreamChunk> chunks = new ArrayList<>();
        List<String> prefillDone = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        for (Stream stream : streams.values()) {
            if (stream.error != null) {
                streams.clear();
                throw new EngineFailureException("vLLM 스트림 실패: " + stream.error.getMessage(), stream.error);
            }
            String token;
            while ((token = stream.tokens.poll()) != null) {
                boolean first = stream.seq == 0;
                if (first) {
                    prefillDone.add(stream.requestId);
                }
                chunks.add(new StreamChunk(stream.requestId, stream.seq++, token, first, false));
            }
            if (stream.done) {
                if (stream.seq == 0) {
                    prefillDone.add(stream.requestId);
                }
                chunks.add(new StreamChunk(stream.requestId, stream.seq++, "", stream.seq == 1, true));
                finished.add(stream.requestId);
            }
        }
        finished.forEach(streams::remove);
        return new StepResult(chunks, prefillDone, finished);
    }

    @Override
    public void loadWeights(StageResponse stage) {
        log.info("event=engine.weights-loaded stage={} weightUri={} msg=real 엔진은 시작 설정의 가중치를 쓴다",
                stage.name(), stage.weightUri());
    }

    @Override
    public boolean generationTest() {
        try {
            HttpResponse<String> response = httpClient.send(request("생성 시험", 1, false),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("event=engine.self-test-failed msg=vLLM 생성 시험 실패: {}", e.getMessage());
            return false;
        }
    }

    private void open(String requestId, String prompt) {
        Stream stream = new Stream(requestId);
        streams.put(requestId, stream);
        httpClient.sendAsync(request(prompt, config.vllmMaxTokens(), true), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        stream.error = new IllegalStateException("HTTP " + response.statusCode());
                        return;
                    }
                    response.body().forEach(line -> consume(stream, line));
                    stream.done = true;
                })
                .exceptionally(e -> {
                    stream.error = e;
                    return null;
                });
    }

    private void consume(Stream stream, String line) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) {
            stream.done = true;
            return;
        }
        try {
            JsonNode text = objectMapper.readTree(data).path("choices").path(0).path("text");
            if (!text.isMissingNode() && !text.asText().isEmpty()) {
                stream.tokens.add(text.asText());
            }
        } catch (Exception e) {
            stream.error = e;
        }
    }

    private HttpRequest request(String prompt, int maxTokens, boolean stream) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", config.vllmModel(), "prompt", prompt, "max_tokens", maxTokens, "stream", stream));
            return HttpRequest.newBuilder(URI.create(config.vllmUrl() + "/v1/completions"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            throw new EngineFailureException("vLLM 요청 생성 실패", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Stream {
        final String requestId;
        final ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
        volatile boolean done;
        volatile Throwable error;
        int seq;

        Stream(String requestId) {
            this.requestId = requestId;
        }
    }
}
