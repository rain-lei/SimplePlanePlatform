package com.proxy.common.transport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlowPermitTest {

    // ---- release 基础行为 ----

    @Test
    void release_callsCallback() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit permit = new FlowPermit(count::incrementAndGet);

        permit.release();

        assertEquals(1, count.get());
    }

    @Test
    void release_isIdempotent() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit permit = new FlowPermit(count::incrementAndGet);

        permit.release();
        permit.release();
        permit.release();

        assertEquals(1, count.get(), "callback must be invoked exactly once");
    }

    @Test
    void isReleased_returnsTrueAfterRelease() {
        FlowPermit permit = new FlowPermit(() -> {});
        assertFalse(permit.isReleased());
        permit.release();
        assertTrue(permit.isReleased());
    }

    // ---- NOOP ----

    @Test
    void noop_releaseDoesNothing() {
        assertDoesNotThrow(() -> {
            FlowPermit.NOOP.release();
            FlowPermit.NOOP.release();
        });
        assertFalse(FlowPermit.NOOP.isReleased());
    }

    // ---- whenComplete ----

    @Test
    void whenComplete_releasesWhenFutureSucceeds() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit permit = new FlowPermit(count::incrementAndGet);
        CompletableFuture<String> future = new CompletableFuture<>();

        permit.whenComplete(future);
        assertEquals(0, count.get(), "should not release before future completes");

        future.complete("ok");
        assertEquals(1, count.get(), "should release after future completes");
    }

    @Test
    void whenComplete_releasesWhenFutureFails() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit permit = new FlowPermit(count::incrementAndGet);
        CompletableFuture<String> future = new CompletableFuture<>();

        permit.whenComplete(future);
        future.completeExceptionally(new RuntimeException("err"));

        assertEquals(1, count.get(), "should release even on failure");
    }

    @Test
    void whenComplete_returnsOriginalFuture() {
        FlowPermit permit = new FlowPermit(() -> {});
        CompletableFuture<String> future = new CompletableFuture<>();

        CompletableFuture<String> returned = permit.whenComplete(future);

        assertSame(future, returned, "whenComplete must return the same future instance");
    }

    // ---- merge ----

    @Test
    void merge_emptyListReturnsNoop() {
        FlowPermit merged = FlowPermit.merge(Collections.emptyList());
        assertSame(FlowPermit.NOOP, merged);
    }

    @Test
    void merge_singlePermitReturnsSame() {
        FlowPermit p = new FlowPermit(() -> {});
        assertSame(p, FlowPermit.merge(Collections.singletonList(p)));
    }

    @Test
    void merge_releaseCallsAllChildren() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit p1 = new FlowPermit(count::incrementAndGet);
        FlowPermit p2 = new FlowPermit(count::incrementAndGet);
        FlowPermit p3 = new FlowPermit(count::incrementAndGet);

        FlowPermit merged = FlowPermit.merge(Arrays.asList(p1, p2, p3));
        merged.release();

        assertEquals(3, count.get(), "all 3 children must be released");
    }

    @Test
    void merge_isIdempotentAcrossChildren() {
        AtomicInteger count = new AtomicInteger(0);
        FlowPermit p1 = new FlowPermit(count::incrementAndGet);
        FlowPermit p2 = new FlowPermit(count::incrementAndGet);

        FlowPermit merged = FlowPermit.merge(Arrays.asList(p1, p2));
        merged.release();
        merged.release();  // second call should be no-op

        assertEquals(2, count.get(), "each child released exactly once");
    }
}
