package io.loglens.web;

/** One typed live event; record and telemetry retain independent durable sequence spaces. */
public record PublishedEvent(String type, long sequence, Object payload) {}
