package io.loglens.processors;

import io.loglens.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class TemplateProcessor extends AbstractLogProcessor {
    private record Rule(String id, Pattern pattern, String replacement) {}
    private final boolean preferOriginal;
    private final List<Rule> rules;
    public TemplateProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        super(definition, dependencies);
        Settings settings = new Settings(config(), nodeId());
        settings.only("preferOriginalTemplate", "replacements");
        preferOriginal = settings.bool("preferOriginalTemplate", true);
        List<Rule> compiled = new ArrayList<>();
        for (Map<String, Object> values : settings.maps("replacements")) {
            Settings rule = new Settings(values, nodeId() + ".replacements");
            rule.only("id", "pattern", "replacement");
            String id = rule.string("id", "normalize");
            Pattern pattern = ProcessorRules.compile(rule.string("pattern", null), nodeId());
            String replacement = rule.string("replacement", "{value}");
            ProcessorRules.replacement(pattern, replacement, nodeId());
            compiled.add(new Rule(id, pattern, replacement));
        }
        rules = List.copyOf(compiled);
    }
    @Override public void process(LogContext context, ChainContinuation next) {
        String original = context.event().template();
        boolean parameterized = !original.isBlank() && (original.contains("{}") || !context.event().parameters().isEmpty());
        String template = preferOriginal && parameterized ? original : context.event().message();
        if (!(preferOriginal && parameterized)) {
            for (Rule rule : rules) {
                String changed = rule.pattern().matcher(template).replaceAll(rule.replacement());
                if (!changed.equals(template)) hit(context, rule.id(), "NORMALIZE", "dynamic template field");
                template = changed;
            }
        }
        context.template(template);
        next.proceed(context);
    }
}
