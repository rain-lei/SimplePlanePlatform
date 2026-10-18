package io.loglens.api;

import java.util.List;

public final class GroupKey {
    private GroupKey() {}
    public static String encode(List<String> by, List<String> values) {
        if (by.size() != values.size()) throw new IllegalArgumentException("group arity");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < by.size(); i++) {
            String key = by.get(i), value = values.get(i);
            result.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value);
        }
        return result.toString();
    }
    public static String value(LogContext context, String field) {
        return switch (field) {
            case "logger" -> context.event().logger();
            case "level" -> context.event().level().name();
            case "template" -> context.template();
            case "exceptionType" -> context.event().exceptionType();
            default -> context.fields().getOrDefault(field, context.event().mdc().getOrDefault(field, ""));
        };
    }
}
