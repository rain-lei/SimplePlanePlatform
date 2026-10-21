package io.loglens.web;

import io.loglens.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EventHubTest {
    private ProcessedRecord record(long sequence) {
        return ProcessedRecord.from("s", sequence, new LogContext(new CapturedLog("e" + sequence, "s", sequence,
                1, 1, 1, LogLevel.INFO, "l", "t", "{}", "safe", List.of(), Map.of(), null, List.of(), false)));
    }
    @Test void subscriptionBoundarySeparatesHistoryFromRealtime() throws Exception {
        try (EventHub hub = new EventHub(2, 2, 0)) {
            hub.publish(record(1));
            try (var subscription = hub.subscribe()) {
                assertEquals(1, subscription.watermark());
                hub.publish(record(2));
                assertEquals(2, subscription.poll(1).recordSequence());
                assertNull(subscription.poll(1));
            }
        }
    }
    @Test void slowClientOverflowDoesNotAffectOtherClients() throws Exception {
        try (EventHub hub = new EventHub(1, 2, 0);
             var slow = hub.subscribe(); var fast = hub.subscribe()) {
            hub.publish(record(1)); assertEquals(1, fast.poll(1).recordSequence());
            hub.publish(record(2));
            assertTrue(slow.gap()); assertTrue(slow.closed());
            assertEquals(2, fast.poll(1).recordSequence()); assertFalse(fast.gap());
            assertEquals(1, hub.clients());
        }
    }
    @Test void limitsAndSequenceOrderingAreEnforced() {
        try (EventHub hub = new EventHub(1, 1, 0); var subscription = hub.subscribe()) {
            assertThrows(IllegalStateException.class, hub::subscribe);
            hub.publish(record(1));
            assertThrows(IllegalArgumentException.class, () -> hub.publish(record(1)));
        }
    }
}
