package com.llmserving.modelmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmserving.common.DrainStartResponse;
import com.llmserving.common.QualitySample;
import com.llmserving.modelmanager.adapter.MockModelStoreDAO;
import com.llmserving.modelmanager.config.ApiException;
import com.llmserving.modelmanager.config.ModelConfigStore;
import com.llmserving.modelmanager.config.ModelManagerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/** sd6: 교체 요청 → 게이트 → 한 대씩 드레인·재적재·준비 → 비율 갱신 → 확정, 품질 이상 → U0 롤백. */
class ModelConfigTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private ModelManagerProperties properties;
    private ModelConfig config;
    private QualityGate gate;

    @BeforeEach
    void setUp() {
        properties = new ModelManagerProperties("model-manager", "llama-3.1-8b", "U0", "v1", "",
                dir.resolve("model-config.properties").toString(), 5000, 60000,
                new ModelManagerProperties.Gate(0.7, Map.of("U0", 0.9, "FP8", 0.86, "SMALL", 0.62), Map.of()),
                new ModelManagerProperties.Rollback(0.6, 3),
                new ModelManagerProperties.Store(0, "", "", List.of(
                        new ModelManagerProperties.StageConfig("U0", "s3://w/u0", 6.0),
                        new ModelManagerProperties.StageConfig("FP8", "s3://w/fp8", 10.0),
                        new ModelManagerProperties.StageConfig("SMALL", "s3://w/small", 14.0))),
                List.of());
        Ref<ModelConfig> ref = new Ref<>();
        gate = new QualityGate(new MockModelStoreDAO(properties), new RollbackManager(ref), properties);
        config = new ModelConfig(gate, new ModelConfigStore(properties), clock, properties);
        ref.value = config;
        for (int i = 1; i <= 5; i++) {
            config.configFor("worker-" + i, "v1");
        }
    }

    private void requestSwap(String stage) {
        new ModelSwapController(config).requestSwap("llama-3.1-8b", stage);
    }

    /** 워커 한 대의 교체 절차 (WorkerAgent가 하는 일). */
    private DrainStartResponse swapWorker(String workerId) {
        DrainStartResponse grant = config.onDrainStart(workerId);
        if (grant.granted()) {
            config.onReady(workerId);
        }
        return grant;
    }

    @Test
    void 품질_게이트를_통과하지_못한_단계는_교체하지_않는다() {
        assertThatThrownBy(() -> requestSwap("SMALL")).isInstanceOf(ApiException.class)
                .hasMessageContaining("품질 게이트");
        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_IDLE);
        assertThat(config.ratioResponse().targetVersion()).isNull();

        requestSwap("FP8");
        assertThat(config.ratioResponse().targetVersion()).isEqualTo("v2");   // 거부된 요청은 번호를 쓰지 않는다
    }

    @Test
    void 교체_중에는_다른_교체를_받지_않는다() {
        requestSwap("FP8");
        assertThatThrownBy(() -> requestSwap("FP8")).isInstanceOf(ApiException.class);
    }

    @Test
    void 워커는_한_대씩_드레인_허가를_받는다() {
        requestSwap("FP8");

        assertThat(config.onDrainStart("worker-5").granted()).isTrue();
        assertThat(config.onDrainStart("worker-4").granted()).isFalse();

        config.onReady("worker-5");
        assertThat(config.onDrainStart("worker-4").granted()).isTrue();
    }

    @Test
    void 준비된_워커가_늘수록_v2_비율이_오르고_모두_준비되면_활성으로_확정한다() {
        requestSwap("FP8");
        assertThat(config.ratioResponse().targetVersion()).isEqualTo("v2");
        assertThat(config.getRatio()).isZero();

        config.onDrainStart("worker-5");
        assertThat(config.getRatio()).isZero();           // 드레인 중: 인출 중 4대, 준비 0
        config.onReady("worker-5");
        assertThat(config.getRatio()).isEqualTo(0.2);      // 1 / 5

        swapWorker("worker-4");
        swapWorker("worker-3");
        assertThat(config.getRatio()).isEqualTo(0.6);
        swapWorker("worker-2");

        DrainStartResponse last = config.onDrainStart("worker-1");
        assertThat(last.lastOfVersion()).isTrue();          // v1을 인출하는 마지막 워커
        assertThat(config.getRatio()).isEqualTo(1.0);       // 새 요청은 모두 v2
        config.onReady("worker-1");

        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_COMPLETED);
        assertThat(config.currentStage().getName()).isEqualTo("FP8");
        assertThat(config.ratioResponse().activeVersion()).isEqualTo("v2");
        assertThat(config.ratioResponse().targetVersion()).isNull();
    }

    @Test
    void 교체_결과는_model_config_파일_한_곳에_기록되고_다시_시작해도_이어진다() throws Exception {
        requestSwap("FP8");
        for (int i = 5; i >= 1; i--) {
            swapWorker("worker-" + i);
        }

        String file = Files.readString(dir.resolve("model-config.properties"));
        assertThat(file).contains("activeStage=FP8").contains("activeVersion=v2");

        ModelConfig restarted = new ModelConfig(gate, new ModelConfigStore(properties), clock, properties);
        assertThat(restarted.currentStage().getName()).isEqualTo("FP8");
    }

    @Test
    void 교체_도중_품질_이상이면_교체를_취소하고_v2_워커를_활성_버전으로_되돌린다() {
        requestSwap("FP8");
        swapWorker("worker-5");
        swapWorker("worker-4");

        for (int i = 0; i < 3; i++) {
            gate.observe(new QualitySample("r" + i, "v2", 0.3));
        }

        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_ROLLING_BACK);
        assertThat(config.ratioResponse().targetVersion()).isNull();
        assertThat(config.onDrainStart("worker-1").granted()).isFalse();   // 이미 v1
        DrainStartResponse back = config.onDrainStart("worker-5");
        assertThat(back.granted()).isTrue();
        assertThat(back.lastOfVersion()).isFalse();                         // worker-4도 아직 v2
        config.onReady("worker-5");
        swapWorker("worker-4");

        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_ROLLED_BACK);
        assertThat(config.currentStage().getName()).isEqualTo("U0");
    }

    @Test
    void 교체_완료_후_품질_이상이면_U0를_새_버전으로_같은_절차로_되돌린다() {
        requestSwap("FP8");
        for (int i = 5; i >= 1; i--) {
            swapWorker("worker-" + i);
        }

        for (int i = 0; i < 3; i++) {
            gate.observe(new QualitySample("r" + i, "v2", 0.2));
        }
        assertThat(config.ratioResponse().targetVersion()).isEqualTo("v3");
        for (int i = 5; i >= 1; i--) {
            swapWorker("worker-" + i);
        }

        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_ROLLED_BACK);
        assertThat(config.currentStage().getName()).isEqualTo("U0");
        assertThat(config.ratioResponse().activeVersion()).isEqualTo("v3");
    }

    @Test
    void 품질이_기준_이상이면_롤백하지_않는다() {
        requestSwap("FP8");
        for (int i = 0; i < 5; i++) {
            gate.observe(new QualitySample("r" + i, "v2", 0.8));
        }
        assertThat(config.getStatus()).isEqualTo(ModelConfig.STATUS_SWAPPING);
    }

    @Test
    void 설정_조회가_끊긴_워커는_비율에서_빠진다() {
        requestSwap("FP8");
        swapWorker("worker-5");
        clock.advanceMs(6_000);
        config.configFor("worker-1", "v1");
        config.configFor("worker-5", "v2");

        assertThat(config.getRatio()).isEqualTo(0.5);   // 살아 있는 2대 중 1대
    }

    static class Ref<T> implements ObjectProvider<T> {
        T value;

        @Override
        public T getObject() throws BeansException {
            return value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }
    }

    static class MutableClock extends Clock {
        private long millis = 1_000_000L;

        void advanceMs(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
