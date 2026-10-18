package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.LogContext;
import io.loglens.api.LogLevel;
import io.loglens.api.ProcessedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HistoryScaleTest {
    private static final int ROWS = 100_000;
    @TempDir Path directory;

    @Test void rebuildsAndKeysetPagesOneHundredThousandRecords() throws Exception {
        Path session = directory.resolve("session"); Files.createDirectory(session);
        try (BufferedWriter writer = Files.newBufferedWriter(session.resolve("processed-0001.jsonl"), StandardCharsets.UTF_8)) {
            for (int sequence = 1; sequence <= ROWS; sequence++) {
                CapturedLog event = new CapturedLog("session:e:" + sequence, "session", sequence, sequence, sequence,
                        sequence, LogLevel.INFO, "orders", "main", "orderId={}", "orderId=O-" + sequence,
                        List.of(), Map.of(), null, List.of(), false);
                writer.write(JsonCodec.string(ProcessedRecord.from("session", sequence, new LogContext(event))));
                writer.newLine();
            }
        }
        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            assertEquals(ROWS, history.watermark());
            long cursor = 0;
            long expectedSequence = 1;
            int pages = 0;
            while (cursor < ROWS) {
                var page = history.records(RecordQuery.page(cursor, 1000));
                assertFalse(page.items().isEmpty());
                for (ProcessedRecord record : page.items()) assertEquals(expectedSequence++, record.recordSequence());
                cursor = page.nextCursor();
                pages++;
                assertEquals(expectedSequence - 1, cursor);
                assertEquals(cursor < ROWS, page.hasMore());
            }
            assertEquals(ROWS + 1L, expectedSequence);
            assertEquals(100, pages);
            assertTrue(history.records(RecordQuery.page(ROWS - 1000, 1000)).items().get(999).message().endsWith("O-" + ROWS));
            assertFalse((Boolean) history.session().get("complete"), "missing metadata must remain an incomplete recovered session");
        }
    }
}
