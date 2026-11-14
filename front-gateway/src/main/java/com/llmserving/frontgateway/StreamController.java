package com.llmserving.frontgateway;

import com.llmserving.common.ApiPaths;
import com.llmserving.common.StreamChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * StreamRelay의 REST 입구: 워커의 토큰 푸시(StreamPort), broker의 실패 통지(FailureNotifier). 위임만 한다.
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private final StreamRelay streamRelay;

    @PostMapping(ApiPaths.FrontGateway.STREAM_CHUNKS)
    public ResponseEntity<Void> chunk(@PathVariable String requestId, @RequestBody StreamChunk chunk) {
        if (!requestId.equals(chunk.requestId())) {
            throw new IllegalArgumentException("경로와 본문의 requestId가 다릅니다");
        }
        streamRelay.onChunk(chunk);
        return ResponseEntity.accepted().build();
    }

    @PostMapping(ApiPaths.FrontGateway.FAILURES)
    public ResponseEntity<Void> failure(@PathVariable String requestId) {
        streamRelay.notifyFailure(requestId);
        return ResponseEntity.accepted().build();
    }
}
