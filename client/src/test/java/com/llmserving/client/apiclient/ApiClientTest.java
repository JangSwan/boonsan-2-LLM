package com.llmserving.client.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.StreamChunk;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiClientTest {

    private HttpServer server;

    private String serve(String sse) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(ApiPaths.FrontGateway.QUESTIONS, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 순번_안내를_받고_토큰을_마지막_조각까지_읽는다() throws Exception {
        String url = serve("""
                event:position
                data:{"requestId":"r","position":2,"epoch":0}

                event:token
                data:{"requestId":"r","seq":0,"token":"안녕","isFirst":true,"isLast":false}

                event:token
                data:{"requestId":"r","seq":1,"token":"하세요","isFirst":false,"isLast":true}

                """);
        List<Integer> positions = new ArrayList<>();

        try (Stream<StreamChunk> chunks = new ApiClient(url).ask("질문", positions::add)) {
            assertThat(chunks.map(StreamChunk::token)).containsExactly("안녕", "하세요");
        }
        assertThat(positions).containsExactly(2);
    }

    @Test
    void 실패_이벤트를_받으면_예외를_던진다() throws Exception {
        String url = serve("""
                event:failure
                data:{"code":"REQUEST_QUARANTINED","message":"재시도 상한","requestId":"r","instanceId":"fg","timestamp":1}

                """);

        try (Stream<StreamChunk> chunks = new ApiClient(url).ask("질문")) {
            assertThatThrownBy(chunks::toList).isInstanceOf(ApiClientException.class)
                    .hasMessageContaining("REQUEST_QUARANTINED");
        }
    }
}
