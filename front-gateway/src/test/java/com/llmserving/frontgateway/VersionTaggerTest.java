package com.llmserving.frontgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.common.RatioResponse;
import com.llmserving.common.Request;
import com.llmserving.frontgateway.adapter.ModelConfigPort;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class VersionTaggerTest {

    private static final Request REQUEST = new Request("r", null, 1, 0, "q");

    private static VersionTagger tagger(RatioResponse ratio, double random) {
        ModelConfigPort port = new ModelConfigPort() {
            @Override
            public double getRatio() {
                return ratio.ratio();
            }

            @Override
            public RatioResponse currentRatio() {
                return ratio;
            }
        };
        RandomGenerator fixed = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return random;
            }
        };
        return new VersionTagger(port, fixed);
    }

    @Test
    void 교체_중이_아니면_활성_버전을_붙인다() {
        assertThat(tagger(new RatioResponse(0, "v1", null), 0.0).tag(REQUEST)).isEqualTo("v1");
    }

    @Test
    void 비율보다_작은_난수면_목표_버전을_붙인다() {
        RatioResponse ratio = new RatioResponse(0.2, "v1", "v2");
        assertThat(tagger(ratio, 0.19).tag(REQUEST)).isEqualTo("v2");
        assertThat(tagger(ratio, 0.2).tag(REQUEST)).isEqualTo("v1");
    }

    @Test
    void 비율이_0이면_목표_버전이_있어도_활성_버전만_붙인다() {
        assertThat(tagger(new RatioResponse(0, "v1", "v2"), 0.0).tag(REQUEST)).isEqualTo("v1");
    }
}
