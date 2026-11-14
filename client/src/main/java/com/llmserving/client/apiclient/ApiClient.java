package com.llmserving.client.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.ErrorResponse;
import com.llmserving.common.QuestionRequest;
import com.llmserving.common.StreamChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 외부 프로그램용 질문 API 클라이언트 (EA ApiClient, boundary). Web UI와 같은 API를 쓴다:
 * POST /api/front-gateway/questions 한 번으로 보내고, 응답 본문을 SSE 형식 스트림으로 읽는다.
 * <pre>
 *   ApiClient client = new ApiClient("http://localhost:8080");
 *   client.ask("질문").forEach(chunk -> System.out.print(chunk.token()));
 * </pre>
 */
public class ApiClient {

    private final URI questionsUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiClient(String frontGatewayUrl) {
        this.questionsUri = URI.create(frontGatewayUrl + ApiPaths.FrontGateway.QUESTIONS);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 질문을 보내고 토큰 조각 스트림을 돌려준다. 대기 순번 안내는 건너뛴다. */
    public Stream<StreamChunk> ask(String text) {
        return ask(text, position -> { });
    }

    /**
     * 질문을 보내고 토큰 조각 스트림을 돌려준다. 순번 안내는 onPosition으로 받는다.
     * 실패 이벤트(재시도 상한 도달 등)를 받으면 스트림에서 ApiClientException을 던진다.
     */
    public Stream<StreamChunk> ask(String text, IntConsumer onPosition) {
        try {
            String body = objectMapper.writeValueAsString(new QuestionRequest(text));
            HttpRequest request = HttpRequest.newBuilder(questionsUri)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new ApiClientException("질문 접수 실패: HTTP " + response.statusCode());
            }
            Iterator<StreamChunk> chunks = new SseChunkIterator(response.body().iterator(), onPosition);
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(chunks, Spliterator.ORDERED), false)
                    .onClose(response.body()::close);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiClientException("중단됨");
        }
    }

    /** 명령줄 사용: java ... ApiClient [front-gateway 주소] "질문" */
    public static void main(String[] args) {
        String url = args.length > 1 ? args[0] : "http://localhost:8080";
        String text = args.length > 1 ? args[1] : args.length == 1 ? args[0] : "안녕하세요";
        try (Stream<StreamChunk> chunks = new ApiClient(url).ask(text,
                position -> System.err.println("[대기 " + position + "번째]"))) {
            chunks.forEach(chunk -> System.out.print(chunk.token()));
        }
        System.out.println();
    }

    /** 응답 본문을 SSE 이벤트 단위로 읽어 token 이벤트만 StreamChunk로 바꾼다. */
    private final class SseChunkIterator implements Iterator<StreamChunk> {

        private final Iterator<String> lines;
        private final IntConsumer onPosition;
        private StreamChunk next;
        private boolean done;

        SseChunkIterator(Iterator<String> lines, IntConsumer onPosition) {
            this.lines = lines;
            this.onPosition = onPosition;
        }

        @Override
        public boolean hasNext() {
            while (next == null && !done) {
                readEvent();
            }
            return next != null;
        }

        @Override
        public StreamChunk next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            StreamChunk chunk = next;
            next = null;
            if (chunk.isLast()) {
                done = true;
            }
            return chunk;
        }

        private void readEvent() {
            String event = null;
            StringBuilder data = new StringBuilder();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.isEmpty()) {
                    if (event != null || !data.isEmpty()) {
                        handle(event, data.toString());
                        return;
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (event != null) {
                handle(event, data.toString());
            }
            done = true;
        }

        private void handle(String event, String data) {
            try {
                switch (event == null ? "" : event) {
                    case ApiPaths.SseEvents.TOKEN -> next = objectMapper.readValue(data, StreamChunk.class);
                    case ApiPaths.SseEvents.POSITION ->
                            onPosition.accept(objectMapper.readTree(data).path("position").asInt());
                    case ApiPaths.SseEvents.FAILURE -> {
                        done = true;
                        ErrorResponse error = objectMapper.readValue(data, ErrorResponse.class);
                        throw new ApiClientException(error.code() + ": " + error.message());
                    }
                    default -> { }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
