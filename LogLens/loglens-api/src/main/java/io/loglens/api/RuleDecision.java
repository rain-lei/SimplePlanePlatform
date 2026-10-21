package io.loglens.api;

public record RuleDecision(String nodeId, String ruleId, String action, String reason) {}
