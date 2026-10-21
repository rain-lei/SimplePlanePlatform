package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.ProcessedRecord;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

/** Streaming index reconstruction. A corrupt complete interior row is an error, not silently skipped. */
public final class JsonlRecovery {
    public record Result(long captured, long processed, long groups, long telemetry, int incompleteTails) {}
    @FunctionalInterface private interface Row { void accept(byte[] bytes, SegmentWriter.Pointer pointer) throws IOException, SQLException; }
    private JsonlRecovery() {}
    public static Result rebuild(Path directory, Path destination) throws IOException, SQLException {
        if (Files.exists(destination)) throw new IllegalArgumentException("recovery index must be new");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        NativeLibraries.configure(destination.toAbsolutePath().getParent().getParent().resolve("native"));
        long[] counts = new long[4]; int tails = 0;
        try (SqliteIndex index = new SqliteIndex(directory, destination, true)) {
            for (Path file : segments(directory, "captured")) tails += scan(file, (bytes, pointer) -> { index.captured(JsonCodec.read(bytes, CapturedLog.class), pointer); counts[0]++; });
            for (Path file : segments(directory, "groups")) tails += scan(file, (bytes, pointer) -> {
                GroupAssociation group = JsonCodec.read(bytes, GroupAssociation.class);
                index.associate(group.captureSequence(), group.groupBy(), group.groupValues(), pointer.segment()); counts[2]++;
            });
            for (Path file : segments(directory, "processed")) tails += scan(file, (bytes, pointer) -> { index.processed(JsonCodec.read(bytes, ProcessedRecord.class), pointer); counts[1]++; });
            for (Path file : segments(directory, "telemetry")) tails += scan(file, (bytes, pointer) -> { index.telemetry(JsonCodec.read(bytes, io.loglens.api.TelemetryEvent.class), pointer); counts[3]++; });
            index.flush();
        }
        return new Result(counts[0], counts[1], counts[2], counts[3], tails);
    }
    private static List<Path> segments(Path directory, String prefix) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches(prefix + "-[0-9]+\\.jsonl"))
                    .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().substring(prefix.length() + 1, p.getFileName().toString().length() - 6)))).toList();
        }
    }
    private static int scan(Path file, Row row) throws IOException, SQLException {
        long offset = 0; String filename = file.getFileName().toString();
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file), 65536)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    byte[] bytes = line.toByteArray();
                    if (bytes.length != 0) row.accept(bytes, new SegmentWriter.Pointer(filename, offset, bytes.length));
                    offset += bytes.length + 1; line.reset();
                } else {
                    if (line.size() == JsonCodec.MAX_ROW_BYTES) throw new IOException("JSONL row exceeds maximum in " + filename);
                    line.write(value);
                }
            }
            if (line.size() != 0) {
                byte[] bytes = line.toByteArray();
                // Only JSON syntax errors at EOF can be ignored; database failures must propagate.
                try { JsonCodec.MAPPER.readTree(bytes); }
                catch (IOException incomplete) { return 1; }
                row.accept(bytes, new SegmentWriter.Pointer(filename, offset, bytes.length));
            }
        }
        return 0;
    }
}
