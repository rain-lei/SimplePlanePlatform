package io.loglens.storage;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class JsonCodec {
    public static final int MAX_ROW_BYTES = 16 * 1024 * 1024;
    public static final ObjectMapper MAPPER = new ObjectMapper();
    static { MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(2 * 1024 * 1024).maxNestingDepth(64).build()); }
    private JsonCodec() {}
    public static byte[] bytes(Object value) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(value);
        if (bytes.length > MAX_ROW_BYTES) throw new IOException("structured row exceeds bounded size");
        return bytes;
    }
    public static String string(Object value) throws IOException { return MAPPER.writeValueAsString(value); }
    public static <T> T read(byte[] bytes, Class<T> type) throws IOException { return MAPPER.readValue(bytes, type); }
}
