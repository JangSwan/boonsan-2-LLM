package com.llmserving.frontgateway;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.ErrorCode;
import com.llmserving.common.ErrorResponse;
import com.llmserving.common.PositionResponse;
import com.llmserving.common.StreamChunk;
import com.llmserving.frontgateway.config.FrontGatewayProperties;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 순번 안내, 토큰, 실패 응답을 SSE 세션으로 전달 (EA StreamRelay). EA에서는 broker의 FailureNotifier를 구현하지만,
 * 앱이 달라 코드로 구현할 수 없으므로 front-gateway StreamController가 받은 실패 통지를 notifyFailure()로 넘긴다.
 */
@Slf4j
@Component
public class StreamRelay {

    private final DuplicateFilter duplicateFilter;
    private final Clock clock;
    private final FrontGatewayProperties properties;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public StreamRelay(DuplicateFilter duplicateFilter, Clock clock, FrontGatewayProperties properties) {
        this.duplicateFilter = duplicateFilter;
        this.clock = clock;
        this.properties = properties;
    }

    /** 요청 하나의 SSE 연결을 연다. */
    public SseSession open(String requestId) {
        SseSession session = new SseSession(UUID.randomUUID().toString(), requestId);
        SseEmitter emitter = new SseEmitter(properties.sseTimeoutMs());
        Channel channel = new Channel(session, emitter);
        emitter.onCompletion(() -> close(requestId));
        emitter.onTimeout(() -> close(requestId));
        emitter.onError(e -> close(requestId));
        channels.put(requestId, channel);
        return session;
    }

    public SseEmitter emitterOf(String requestId) {
        Channel channel = channels.get(requestId);
        if (channel == null) {
            throw new IllegalStateException("SSE 세션이 없습니다: " + requestId);
        }
        return channel.emitter;
    }

    /** 대기 순번 안내 (sd1 8번). 첫 토큰 이후에는 보내지 않고, 같은 순번은 다시 보내지 않는다. */
    public void notifyPosition(String requestId, int position) {
        Channel channel = channels.get(requestId);
        if (channel == null || channel.session.isStreaming() || position <= 0
                || position == channel.session.getLastPosition()) {
            return;
        }
        if (channel.send(ApiPaths.SseEvents.POSITION, new PositionResponse(requestId, position, 0))) {
            channel.session.positionSent(position);
            log.info("event=request.position requestId={} position={} msg=대기 순번 안내", requestId, position);
        }
    }

    /**
     * 토큰 조각 (sd1 22번). DuplicateFilter를 통과한 조각만 보내고, 마지막 조각이면 SSE를 닫는다.
     * 재시도된 요청은 순번을 0부터 다시 세는데, 실제 엔진은 같은 질문에도 답 길이가 달라질 수 있다. 다시 생성한 답이
     * 앞서 보낸 것보다 짧으면 마지막 조각의 순번이 이미 보낸 순번 이하라 걸러진다. 이때도 요청은 끝났으므로 본문 없이
     * 마지막 표시만 보내고 SSE를 닫는다 (닫지 않으면 이용자는 SSE 시간 제한까지 기다린다).
     */
    public void onChunk(StreamChunk chunk) {
        Channel channel = channels.get(chunk.requestId());
        if (channel == null) {
            return;
        }
        if (!duplicateFilter.accept(chunk)) {
            if (chunk.isLast()) {
                channel.send(ApiPaths.SseEvents.TOKEN,
                        new StreamChunk(chunk.requestId(), chunk.seq(), "", false, true));
                log.info("event=stream.completed requestId={} lastSeq={} msg=재시도한 답이 더 짧아 마지막 표시만 보내고 SSE 종료",
                        chunk.requestId(), chunk.seq());
                channel.complete();
            }
            return;
        }
        if (channel.send(ApiPaths.SseEvents.TOKEN, chunk)) {
            channel.session.advance(chunk.seq());
        }
        if (chunk.isFirst()) {
            log.info("event=stream.first-token requestId={} msg=첫 토큰 전달", chunk.requestId());
        }
        if (chunk.isLast()) {
            log.info("event=stream.completed requestId={} lastSeq={} msg=답변 완료, SSE 종료",
                    chunk.requestId(), chunk.seq());
            channel.complete();
        }
    }

    /** 실패 응답 (sd4 17번): 재시도 상한에 닿아 격리된 요청. */
    public void notifyFailure(String requestId) {
        notifyError(requestId, ErrorCode.REQUEST_QUARANTINED, "요청을 처리하지 못했습니다 (재시도 상한 도달)");
    }

    /** 실패 이벤트를 보내고 SSE를 닫는다. */
    public void notifyError(String requestId, ErrorCode code, String message) {
        Channel channel = channels.get(requestId);
        if (channel == null) {
            return;
        }
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            log.warn("event=request.failed requestId={} code={} msg={}", requestId, code, message);
        }
        channel.send(ApiPaths.SseEvents.FAILURE,
                new ErrorResponse(code, message, requestId, properties.instanceId(), clock.millis()));
        channel.complete();
    }

    /** 첫 토큰을 아직 받지 못한 (대기 중인) 요청. */
    public List<String> waitingRequestIds() {
        return channels.values().stream().filter(c -> !c.session.isStreaming())
                .map(c -> c.session.getRequestId()).toList();
    }

    public int openSessions() {
        return channels.size();
    }

    private void close(String requestId) {
        channels.remove(requestId);
        duplicateFilter.forget(requestId);
    }

    private static final class Channel {
        final SseSession session;
        final SseEmitter emitter;
        boolean completed;

        Channel(SseSession session, SseEmitter emitter) {
            this.session = session;
            this.emitter = emitter;
        }

        synchronized boolean send(String event, Object data) {
            if (completed) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                completed = true;
                return false;
            }
        }

        synchronized void complete() {
            if (!completed) {
                completed = true;
                emitter.complete();
            }
        }
    }
}
