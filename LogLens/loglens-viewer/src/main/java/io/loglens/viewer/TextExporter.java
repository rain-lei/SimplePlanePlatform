package io.loglens.viewer;

import io.loglens.storage.RecordRepository;
import io.loglens.storage.RecordQuery;
import io.loglens.storage.SessionStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Stream authoritative FINAL records into a new text report without retaining all history. */
public final class TextExporter {
    private TextExporter() {}
    public static void export(RecordRepository repository, Path destination) throws IOException {
        long cursor = 0;
        try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (;;) {
                var page = repository.records(RecordQuery.page(cursor, 500));
                for (var record : page.items()) { writer.write(SessionStorage.renderText(record)); writer.write('\n'); }
                cursor = page.nextCursor();
                if (!page.hasMore() || page.items().isEmpty()) break;
            }
        }
    }
}
