package com.propertyos.common.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A sick service is cut off quickly, reads are retried, the caller's own mistakes do not count, and one service cannot use every thread. */
class ResilienceTest {
    private Resilience small() {
        return new Resilience(50, 4, Duration.ofMillis(150), 2, 3, Duration.ofMillis(1));
    }

    @Test
    void successPassesThrough() {
        assertThat(small().call("BILLING", false, () -> "ok")).isEqualTo("ok");
        assertThat(small().state("NEVER_CALLED")).isEqualTo("CLOSED");
    }

    @Test
    void readsAreRetriedUntilTheyWork() {
        AtomicInteger calls = new AtomicInteger();
        String result = small().call("BILLING", true, () -> {
            if (calls.incrementAndGet() < 3) throw new ResourceAccessException("down");
            return "recovered";
        });
        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void writesAreNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> small().call("BILLING", false, () -> {
            calls.incrementAndGet();
            throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        })).isInstanceOf(HttpServerErrorException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void clientErrorsNeitherRetryNorOpenTheCircuit() {
        Resilience r = small();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 12; i++)
            assertThatThrownBy(() -> r.call("LEASING", true, () -> {
                calls.incrementAndGet();
                throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
            })).isInstanceOf(HttpClientErrorException.class);
        assertThat(calls.get()).isEqualTo(12);
        assertThat(r.state("LEASING")).isEqualTo("CLOSED");
    }

    @Test
    void repeatedFailuresOpenTheCircuitThenItRecovers() throws Exception {
        Resilience r = small();
        for (int i = 0; i < 6; i++)
            assertThatThrownBy(() -> r.call("AI", false, () -> {
                throw new ResourceAccessException("down");
            })).isInstanceOf(ResourceAccessException.class);
        assertThat(r.state("AI")).isEqualTo("OPEN");
        AtomicInteger reached = new AtomicInteger();
        assertThatThrownBy(() -> r.call("AI", false, () -> reached.incrementAndGet()))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("circuit open");
        assertThat(reached.get()).isZero();
        Thread.sleep(200);
        for (int i = 0; i < 3; i++) assertThat(r.call("AI", false, () -> "back")).isEqualTo("back");
        assertThat(r.state("AI")).isEqualTo("CLOSED");
    }

    @Test
    void oneServiceCannotUseUnlimitedConcurrency() throws Exception {
        Resilience r = small();
        CountDownLatch inside = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable slow = () -> r.call("DOCUMENTS", false, () -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });
        Thread a = new Thread(slow), b = new Thread(slow);
        a.start();
        b.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> r.call("DOCUMENTS", false, () -> "third")).isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("concurrent");
        release.countDown();
        a.join();
        b.join();
        assertThat(r.call("DOCUMENTS", false, () -> "after")).isEqualTo("after");
    }

    @Test
    void configurationBuildsTheDefaults() {
        assertThat(new ResilienceConfig().resilience(50, 10, 20, 50, 3, 150).state("X")).isEqualTo("CLOSED");
    }
}
