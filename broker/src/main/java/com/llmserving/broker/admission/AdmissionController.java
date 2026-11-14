package com.llmserving.broker.admission;

import com.llmserving.broker.queue.QueueItem;
import com.llmserving.broker.queue.SharedQueue;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.EnqueueResponse;
import com.llmserving.common.PositionResponse;
import com.llmserving.common.Request;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 접수 창구 (EA AdmissionController, C2). 거절하지 않고 적재한 뒤, 복제가 확인되면 순번을 응답한다.
 * 대기열이 길어도 429를 쓰지 않고 순번으로 안내한다.
 */
@RestController
@RequiredArgsConstructor
public class AdmissionController {

    private final SharedQueue sharedQueue;
    private final Clock clock;

    /** 버전 태그로 적재하고 대기 순번을 돌려준다 (sd1 4~7번). push()는 복제 확인 후 커밋한다. */
    public int enqueue(Request request) {
        validate(request);
        return sharedQueue.push(new QueueItem(request, clock.millis(), 0));
    }

    /** 대기 순번 조회 (sd1 8번 이후 갱신). 이미 인출되었으면 0. */
    public int getPosition(String requestId) {
        return sharedQueue.positionOf(requestId);
    }

    @PostMapping(ApiPaths.Broker.REQUESTS)
    public EnqueueResponse postRequest(@RequestBody Request request) {
        int position = enqueue(request);
        return new EnqueueResponse(request.requestId(), position, sharedQueue.currentEpoch());
    }

    @GetMapping(ApiPaths.Broker.REQUEST_POSITION)
    public PositionResponse getRequestPosition(@PathVariable String requestId) {
        return new PositionResponse(requestId, getPosition(requestId), sharedQueue.currentEpoch());
    }

    private static void validate(Request request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw new IllegalArgumentException("requestId가 필요합니다");
        }
        if (request.versionTag() == null || request.versionTag().isBlank()) {
            throw new IllegalArgumentException("versionTag가 필요합니다");
        }
        if (request.inputTokens() < 0) {
            throw new IllegalArgumentException("inputTokens는 0 이상이어야 합니다");
        }
    }
}
