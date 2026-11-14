package com.llmserving.worker.leaserenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmserving.worker.WorkerTestSupport;
import org.junit.jupiter.api.Test;

class LeaseRenewerTest {

    private final WorkerTestSupport.MutableClock clock = new WorkerTestSupport.MutableClock();
    private final WorkerTestSupport.FakeLeasePort leasePort = new WorkerTestSupport.FakeLeasePort();
    private final LeaseRenewer renewer = new LeaseRenewer(leasePort, clock, WorkerTestSupport.properties());

    @Test
    void 진척이_보고된_요청만_갱신한다() {
        renewer.onProgress("a");

        renewer.renewActive();

        assertThat(leasePort.renewed).containsExactly("a");
    }

    @Test
    void 진척이_끊기면_갱신을_멈춘다() {
        renewer.onFirstToken("x", 0);
        clock.advanceMs(4_001);   // progress-timeout-ms 4000

        renewer.renewActive();

        assertThat(leasePort.renewed).isEmpty();
        assertThat(renewer.isTracking("x")).isFalse();
    }

    @Test
    void 진척이_계속되면_긴_생성도_계속_갱신한다() {
        for (int i = 0; i < 5; i++) {
            renewer.onProgress("long");
            clock.advanceMs(2_000);
            renewer.renewActive();
        }
        assertThat(leasePort.renewed).hasSize(5);
    }

    @Test
    void 완료되거나_broker에_lease가_없으면_더_갱신하지_않는다() {
        renewer.onProgress("done");
        renewer.onProgress("reclaimed");
        renewer.onCompleted("done");
        leasePort.gone.add("reclaimed");

        renewer.renewActive();
        renewer.renewActive();

        assertThat(leasePort.renewed).isEmpty();
        assertThat(renewer.isTracking("reclaimed")).isFalse();
    }
}
