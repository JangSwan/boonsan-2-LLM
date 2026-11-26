package com.llmserving.worker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.StageResponse;
import com.llmserving.common.StreamChunk;
import com.llmserving.worker.config.WorkerProperties;
import com.llmserving.worker.runtime.StepResult;
import com.llmserving.worker.scheduling.StepPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * real 프로필의 추론 엔진: vLLM(OpenAI 호환 /v1/chat/completions, stream: true).
 * Instruct 모델이 질문으로 알아듣도록 본문을 user 메시지로 보내 엔진이 채팅 템플릿을 적용하게 한다.
 * plan.prompts에 처음 나온 요청은 그 본문으로 스트림을 열고, 이후 execute()가 불릴 때마다 지난 호출 이후 받은 토큰을
 * 같은 StepResult 모양으로 돌려준다. 첫 토큰이 온 요청은 prefillDoneIds, 스트림이 끝난 요청은 finishedIds에 넣는다.
 * 스텝 구성은 vLLM의 chunked prefill이 하고, B는 vLLM 시작 설정 max_num_batched_tokens로 넣는다 (CLAUDE.md 2절).
 * 사다리 단계 전환(loadWeights)은 vLLM을 해당 가중치로 다시 띄워야 하므로 여기서는 기록만 한다.
 *
 * <p>실패는 엔진 전체 장애와 요청 하나의 실패로 나눈다 (docs/design.md 5절 "추론 엔진 계약").
 * <ul>
 *   <li>요청 하나의 실패: vLLM이 그 요청만 4xx로 거절했거나(예: 입력이 max-model-len을 넘음), 토큰을 받던 연결 하나가
 *       끊겼다. 엔진은 정상이므로 그 요청만 failedIds로 알리고 나머지 스트림은 계속 받는다.</li>
 *   <li>토큰을 하나도 받기 전에 연결이 끊긴 요청은 그 요청만 다시 연다(출력 전이라 멱등). keep-alive 연결을 vLLM이 먼저
 *       닫아 생기는 경쟁(HTTP/1.1 header parser received no bytes)이 여기에 해당한다. 다시 열어도 연결되지 않으면
 *       엔진이 응답하지 않는 것으로 본다.</li>
 *   <li>엔진 전체 장애: 5xx, 또는 다시 열어도 연결되지 않음. EngineFailureException을 던지고, 진행 중이던 스트림은
 *       모두 닫아 vLLM이 사라진 요청을 계속 생성하지 않게 한다.</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("real")
public class VllmEngineAdapter implements InferenceEnginePort {

    private final WorkerProperties.Engine config;
    private final ObjectMapper objectMapper;
    // vLLM(uvicorn)은 h2c 업그레이드를 받지 않으므로 처음부터 HTTP/1.1로 연결한다
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    public VllmEngineAdapter(WorkerProperties properties, ObjectMapper objectMapper) {
        this.config = properties.engine();
        this.objectMapper = objectMapper;
    }

    @Override
    public StepResult execute(StepPlan plan) {
        plan.prompts().forEach(this::open);
        sleep(config.stepBaseMs());
        for (Stream stream : streams.values()) {
            if (stream.engineError != null) {
                Throwable cause = stream.engineError;
                streams.values().forEach(Stream::close);
                streams.clear();
                throw new EngineFailureException("vLLM 응답 없음: " + cause.getMessage(), cause);
            }
        }
        List<StreamChunk> chunks = new ArrayList<>();
        List<String> prefillDone = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Stream stream : streams.values()) {
            String token;
            while ((token = stream.tokens.poll()) != null) {
                boolean first = stream.seq == 0;
                if (first) {
                    prefillDone.add(stream.requestId);
                }
                chunks.add(new StreamChunk(stream.requestId, stream.seq++, token, first, false));
            }
            if (stream.requestError != null) {
                log.warn("event=engine.stream-failed requestId={} msg=vLLM이 이 요청만 처리하지 못함: {}",
                        stream.requestId, stream.requestError);
                stream.close();
                failed.add(stream.requestId);
            } else if (stream.done) {
                if (stream.seq == 0) {
                    prefillDone.add(stream.requestId);
                }
                chunks.add(new StreamChunk(stream.requestId, stream.seq++, "", stream.seq == 1, true));
                finished.add(stream.requestId);
            }
        }
        finished.forEach(streams::remove);
        failed.forEach(streams::remove);
        return new StepResult(chunks, prefillDone, finished, failed);
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
        Stream stream = new Stream(requestId, prompt);
        streams.put(requestId, stream);
        connect(stream);
    }

    private void connect(Stream stream) {
        stream.attempts++;
        httpClient.sendAsync(request(stream.prompt, config.vllmMaxTokens(), true), HttpResponse.BodyHandlers.ofLines())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        onConnectionError(stream, unwrap(error));
                        return;
                    }
                    if (stream.closed) {
                        response.body().close();
                        return;
                    }
                    int status = response.statusCode();
                    if (status != 200) {
                        response.body().close();
                        if (status >= 400 && status < 500) {
                            stream.requestError = "HTTP " + status;  // 이 요청만 거절됨 (엔진은 정상)
                        } else {
                            stream.engineError = new IllegalStateException("HTTP " + status);
                        }
                        return;
                    }
                    stream.lines = response.body();
                    try {
                        stream.lines.forEach(line -> consume(stream, line));
                        stream.done = true;
                    } catch (UncheckedIOException e) {
                        onConnectionError(stream, e.getCause());
                    } catch (RuntimeException e) {
                        if (!stream.closed) {
                            stream.requestError = e.toString();
                        }
                    }
                });
    }

    /** 연결 오류: 토큰을 받기 전이면 그 요청만 다시 연다. 받은 뒤라면 그 요청만 실패다. */
    private void onConnectionError(Stream stream, Throwable error) {
        if (stream.closed) {
            return;
        }
        if (!stream.receivedAny) {
            if (stream.attempts <= config.vllmOpenRetries()) {
                log.info("event=engine.stream-reopened requestId={} attempt={} msg=첫 토큰 전 연결 오류, 이 요청만 다시 연결: {}",
                        stream.requestId, stream.attempts + 1, error.toString());
                connect(stream);
                return;
            }
            stream.engineError = error;  // 다시 열어도 연결되지 않음: 엔진이 응답하지 않는다
            return;
        }
        stream.requestError = error.toString();
    }

    private void consume(Stream stream, String line) {
        if (stream.closed || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) {
            stream.done = true;
            return;
        }
        try {
            // 첫 조각은 role만 담고 content가 비어 있으므로 건너뛴다
            JsonNode content = objectMapper.readTree(data).path("choices").path(0).path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                stream.receivedAny = true;
                stream.tokens.add(content.asText());
            }
        } catch (IOException e) {
            stream.requestError = "응답 해석 실패: " + e.getMessage();
        }
    }

    private HttpRequest request(String prompt, int maxTokens, boolean stream) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", config.vllmModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", maxTokens, "stream", stream));
            return HttpRequest.newBuilder(URI.create(config.vllmUrl() + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            throw new EngineFailureException("vLLM 요청 생성 실패", e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
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
        final String prompt;
        final ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
        volatile java.util.stream.Stream<String> lines;
        volatile boolean done;
        volatile boolean receivedAny;
        volatile boolean closed;
        /** 이 요청만 실패 (엔진은 정상). */
        volatile String requestError;
        /** 엔진이 응답하지 않음. */
        volatile Throwable engineError;
        volatile int attempts;
        int seq;

        Stream(String requestId, String prompt) {
            this.requestId = requestId;
            this.prompt = prompt;
        }

        /** 받는 중인 스트림을 닫아 vLLM이 이 요청 생성을 멈추게 한다. */
        void close() {
            closed = true;
            java.util.stream.Stream<String> current = lines;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                    // 이미 끝난 스트림
                }
            }
        }
    }
}
