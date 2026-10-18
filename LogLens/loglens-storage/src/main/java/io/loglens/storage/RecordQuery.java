package io.loglens.storage;

/** Keyset cursor; positive after and inclusive snapshot watermark. */
public record RecordQuery(long after, long through, int limit, String level, String logger,
                          String tag, String traceId, String orderId, String search) {
    public RecordQuery {
        if (after < 0 || through < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid record cursor/limit");
        for (String filter : new String[]{level, logger, tag, traceId, orderId, search}) if (filter != null && filter.length() > 512) throw new IllegalArgumentException("filter exceeds 512 characters");
    }
    public static RecordQuery page(long after, int limit) { return new RecordQuery(after, Long.MAX_VALUE, limit, null, null, null, null, null, null); }
}
