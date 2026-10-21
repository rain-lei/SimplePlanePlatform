package io.loglens.storage;

/** Bounded keyset query for persisted proxy telemetry. Null strings disable exact filters. */
public record TelemetryQuery(long after,long through,int limit,String kind,String connectionId,String streamId,
                             String peerAddress,String transport,String target,String status,long fromTime,long toTime) {
    public TelemetryQuery {
        if(after<0||through<0||limit<1||limit>1000||fromTime<0||toTime<fromTime)throw new IllegalArgumentException("telemetry query bounds");
    }
}
