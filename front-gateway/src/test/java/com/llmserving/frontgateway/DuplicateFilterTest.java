package com.llmserving.frontgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.StreamChunk;
import org.junit.jupiter.api.Test;

class DuplicateFilterTest {

    private final DuplicateFilter filter = new DuplicateFilter();

    private static StreamChunk chunk(String id, int seq) {
        return new StreamChunk(id, seq, "t" + seq, seq == 0, false);
    }

    @Test
    void 처음_보는_순번은_통과시킨다() {
        assertThat(filter.accept(chunk("a", 0))).isTrue();
        assertThat(filter.accept(chunk("a", 1))).isTrue();
    }

    @Test
    void 같은_순번이_두_번_오면_두_번째를_버린다() {
        filter.accept(chunk("a", 0));
        assertThat(filter.accept(chunk("a", 0))).isFalse();
    }

    @Test
    void 다른_워커가_처음부터_다시_보내면_이미_보낸_순번까지는_버리고_이어지는_순번부터_통과시킨다() {
        for (int i = 0; i <= 4; i++) {
            filter.accept(chunk("a", i));
        }
        assertThat(filter.accept(chunk("a", 0))).isFalse();
        assertThat(filter.accept(chunk("a", 4))).isFalse();
        assertThat(filter.accept(chunk("a", 5))).isTrue();
    }

    @Test
    void 요청마다_따로_센다() {
        filter.accept(chunk("a", 3));
        assertThat(filter.accept(chunk("b", 0))).isTrue();
    }
}
