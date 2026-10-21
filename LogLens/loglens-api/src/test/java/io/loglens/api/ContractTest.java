package io.loglens.api;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    @Test void definitionsFreezeAllNestedValues() {
        Map<String, Object> nested = new HashMap<>(Map.of("field", "orderId"));
        List<Object> rules = new ArrayList<>(List.of(nested));
        Map<String, Object> root = new HashMap<>(Map.of("rules", rules));
        NodeDefinition node = new NodeDefinition("extract", "custom", root);
        nested.put("field", "changed");
        rules.clear();
        List<?> actual = (List<?>) node.config().get("rules");
        assertEquals("orderId", ((Map<?, ?>) actual.get(0)).get("field"));
        assertThrows(UnsupportedOperationException.class, () -> node.config().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, actual::clear);
    }
    @Test void contextCopiesPreventPluginFailureMutationLeaking() {
        CapturedLog event = new CapturedLog("e", "s", 1, 5, 6, 7, LogLevel.INFO,
                "logger", "thread", "order {}", "order 1", List.of("1"), Map.of("traceId", "t"), null, List.of(), false);
        LogContext input = new LogContext(event);
        input.fields().put("orderId", "1");
        LogContext before = input.copy();
        input.fields().put("orderId", "corrupt");
        input.tags().add("broken");
        assertEquals("1", before.fields().get("orderId"));
        assertTrue(before.tags().isEmpty());
        ProcessedRecord result = ProcessedRecord.from("s", 2, before);
        before.fields().put("orderId", "changed again");
        assertEquals("1", result.fields().get("orderId"));
        assertEquals("s:r:2", result.recordId());
        assertEquals("FINAL", result.status());
    }
    @Test void rejectsApplicationObjectsInsidePluginConfig() {
        assertThrows(IllegalArgumentException.class, () -> new NodeDefinition("n", "p", Map.of("unsafe", new Object())));
    }
}
