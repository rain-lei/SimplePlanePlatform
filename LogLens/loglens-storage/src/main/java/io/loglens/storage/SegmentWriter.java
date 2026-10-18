package io.loglens.storage;

import io.loglens.api.LogClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Consumer-only append with UTF-8 byte offsets, daily/size rotation and closed segment tracking. */
public final class SegmentWriter implements AutoCloseable {
    public record Pointer(String segment, long offset, int length) {}
    public record ClosedSegment(Path path, long closedAt, long bytes) {}
    private final Path directory;
    private final String prefix;
    private final String extension;
    private final long maxBytes;
    private final LogClock clock;
    private final List<ClosedSegment> closed = new ArrayList<>();
    private FileChannel channel;
    private Path current;
    private long size;
    private int number;
    private LocalDate date;
    public SegmentWriter(Path directory, String prefix, String extension, long maxBytes, LogClock clock) {
        this.directory = directory; this.prefix = prefix; this.extension = extension; this.maxBytes = maxBytes; this.clock = clock;
    }
    public Pointer append(byte[] bytes) throws IOException {
        if (bytes.length > JsonCodec.MAX_ROW_BYTES) throw new IOException("row exceeds maximum");
        LocalDate today = Instant.ofEpochMilli(clock.wallTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate();
        if (channel == null || (size > 0 && size + bytes.length + 1 > maxBytes) || !today.equals(date)) {
            rotate();
            current = directory.resolve(prefix + "-" + String.format("%04d", ++number) + "." + extension);
            channel = FileChannel.open(current, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            size = 0; date = today;
        }
        long offset = size;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        ByteBuffer newline = ByteBuffer.wrap(new byte[]{'\n'});
        while (newline.hasRemaining()) channel.write(newline);
        size += bytes.length + 1;
        return new Pointer(current.getFileName().toString(), offset, bytes.length);
    }
    private void rotate() throws IOException {
        if (channel != null) { channel.force(false); channel.close(); closed.add(new ClosedSegment(current, clock.wallTimeMillis(), size)); channel = null; }
    }
    public void flush() throws IOException { if (channel != null) channel.force(false); }
    public Path currentPath() { return current; }
    public List<ClosedSegment> closedSegments() { return List.copyOf(closed); }
    public void forget(Path path) { closed.removeIf(segment -> segment.path().equals(path)); }
    @Override public void close() throws IOException { rotate(); }
}
