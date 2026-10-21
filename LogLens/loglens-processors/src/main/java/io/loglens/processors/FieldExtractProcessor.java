package io.loglens.processors;

import io.loglens.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FieldExtractProcessor extends AbstractLogProcessor {
    private record Rule(String field, Pattern pattern, String group) {}
    private final List<Rule> rules;
    private final Map<String, Object> mapping;
    public FieldExtractProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        super(definition, dependencies);
        Settings settings = new Settings(config(), nodeId());
        settings.only("rules", "mdcMapping");
        List<Rule> compiled = new ArrayList<>();
        for (Map<String, Object> values : settings.maps("rules")) {
            Settings rule = new Settings(values, nodeId() + ".rules");
            rule.only("field", "pattern", "group");
            String field = rule.string("field", null);
            if (field == null || !field.matches("[A-Za-z][A-Za-z0-9._-]{0,79}")) throw rule.error("field", "safe field name required");
            Pattern pattern = ProcessorRules.compile(rule.string("pattern", null), nodeId());
            String group = rule.string("group", "value");
            if (!pattern.pattern().contains("(?<" + group + ">")) throw rule.error("group", "named group missing in pattern");
            compiled.add(new Rule(field, pattern, group));
        }
        rules = List.copyOf(compiled);
        mapping = settings.map("mdcMapping");
        mapping.forEach((k, v) -> { if (!(v instanceof String s) || s.isBlank()) throw settings.error("mdcMapping", "MDC key strings required"); });
    }
    @Override public void process(LogContext context, ChainContinuation next) {
        mapping.forEach((field, key) -> {
            String value = context.event().mdc().get((String) key);
            if (value != null) { context.fields().put(field, value); hit(context, "mdc:" + field, "EXTRACT", "MDC mapping"); }
        });
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(context.event().message());
            if (matcher.find()) {
                String value = matcher.group(rule.group());
                if (value != null) { context.fields().put(rule.field(), value); hit(context, rule.field(), "EXTRACT", "message rule"); }
            }
        }
        next.proceed(context);
    }
}
