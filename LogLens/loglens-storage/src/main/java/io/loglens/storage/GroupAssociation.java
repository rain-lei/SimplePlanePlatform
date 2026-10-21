package io.loglens.storage;

import java.util.List;

public record GroupAssociation(long captureSequence, List<String> groupBy, List<String> groupValues) {
    public GroupAssociation { groupBy = List.copyOf(groupBy); groupValues = List.copyOf(groupValues); }
}
