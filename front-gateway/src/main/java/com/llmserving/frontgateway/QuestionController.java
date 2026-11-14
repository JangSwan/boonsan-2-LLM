package com.llmserving.frontgateway;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.ErrorCode;
import com.llmserving.common.QuestionRequest;
import com.llmserving.common.Request;
import com.llmserving.frontgateway.adapter.AdmissionPort;
import com.llmserving.frontgateway.config.FrontGatewayProperties;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 질문 접수 진입점 (EA QuestionController). SSE 연결을 열고 요청 ID, 버전 태그를 붙여 enqueue한 뒤 순번을 안내한다.
 * 질문은 긴 입력을 담으므로 POST 한 번으로 받고, 같은 응답을 SSE 스트림으로 유지한다.
 */
@Slf4j
@RestController
public class QuestionController {

    private final RequestIdIssuer requestIdIssuer;
    private final VersionTagger versionTagger;
    private final AdmissionPort queueBrokerClient;
    private final StreamRelay streamRelay;
    private final Clock clock;
    private final int charsPerToken;

    public QuestionController(RequestIdIssuer requestIdIssuer, VersionTagger versionTagger,
                              AdmissionPort queueBrokerClient, StreamRelay streamRelay, Clock clock,
                              FrontGatewayProperties properties) {
        this.requestIdIssuer = requestIdIssuer;
        this.versionTagger = versionTagger;
        this.queueBrokerClient = queueBrokerClient;
        this.streamRelay = streamRelay;
        this.clock = clock;
        this.charsPerToken = Math.max(1, properties.charsPerToken());
    }

    /** sd1 2~8번: 요청 ID 발급, 버전 태그, enqueue, 순번 안내. */
    public SseSession receive(String text) {
        String requestId = requestIdIssuer.issue();
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            long now = clock.millis();
            int inputTokens = estimateTokens(text);
            String versionTag = versionTagger.tag(new Request(requestId, null, inputTokens, now, text));
            Request request = new Request(requestId, versionTag, inputTokens, now, text);
            SseSession session = streamRelay.open(requestId);
            log.info("event=request.received requestId={} versionTag={} inputTokens={} msg=질문 접수",
                    requestId, versionTag, inputTokens);
            try {
                int position = queueBrokerClient.enqueue(request);
                streamRelay.notifyPosition(requestId, position);
            } catch (RuntimeException e) {
                streamRelay.notifyError(requestId, ErrorCode.UPSTREAM_UNAVAILABLE, "접수하지 못했습니다: " + e.getMessage());
            }
            return session;
        }
    }

    @PostMapping(value = ApiPaths.FrontGateway.QUESTIONS, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postQuestion(@RequestBody QuestionRequest question) {
        if (question == null || question.text() == null || question.text().isBlank()) {
            throw new IllegalArgumentException("질문 본문(text)이 필요합니다");
        }
        SseSession session = receive(question.text());
        return streamRelay.emitterOf(session.getRequestId());
    }

    /** 주기 작업: 대기 중 요청의 순번 갱신 (docs/design.md 5절 "질문 접수와 스트림"). */
    @Scheduled(fixedDelayString = "${llm.front-gateway.position-poll-interval-ms}")
    public void refreshPositions() {
        for (String requestId : streamRelay.waitingRequestIds()) {
            try {
                streamRelay.notifyPosition(requestId, queueBrokerClient.getPosition(requestId));
            } catch (RuntimeException e) {
                log.debug("event=request.position-poll-failed requestId={} msg=순번 조회 실패, 다음 주기에 다시 시도",
                        requestId);
            }
        }
    }

    private int estimateTokens(String text) {
        int chars = text.codePointCount(0, text.length());
        return Math.max(1, (chars + charsPerToken - 1) / charsPerToken);
    }
}
