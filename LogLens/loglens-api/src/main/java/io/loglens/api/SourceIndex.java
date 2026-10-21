package io.loglens.api;

import java.util.List;

/** Register the actual semantic group for an already persisted captured event. */
@FunctionalInterface
public interface SourceIndex { void associate(long captureSequence, List<String> groupBy, List<String> groupValues); }
