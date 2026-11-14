package com.llmserving.frontgateway;

import com.llmserving.common.StreamChunk;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * lease 경계의 중복 결과를 요청 ID와 순번으로 걸러냄 (EA DuplicateFilter, C2 표준 보완).
 * 이미 보낸 순번 이하의 조각은 버린다. 다른 워커가 같은 요청을 다시 처리해도 이용자는 같은 순번을 두 번 받지 않는다.
 */
@Component
public class DuplicateFilter {

    private final Map<String, Integer> lastSeq = new ConcurrentHashMap<>();

    public boolean accept(StreamChunk chunk) {
        boolean[] accepted = {false};
        lastSeq.compute(chunk.requestId(), (id, last) -> {
            if (last == null || chunk.seq() > last) {
                accepted[0] = true;
                return chunk.seq();
            }
            return last;
        });
        return accepted[0];
    }

    public void forget(String requestId) {
        lastSeq.remove(requestId);
    }
}
