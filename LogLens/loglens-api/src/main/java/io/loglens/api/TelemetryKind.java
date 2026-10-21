package io.loglens.api;

/** Stable event types emitted by optional runtime telemetry adapters. */
public enum TelemetryKind {
    CLIENT_CONNECTED,
    CLIENT_DISCONNECTED,
    SESSION_OPENED,
    SESSION_ACTIVE,
    SESSION_CONNECT_FAILED,
    SESSION_CLOSED,
    TRAFFIC_SAMPLE
}
