package io.loglens.processors;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ProcessorRules {
    private ProcessorRules() {}
    static Pattern compile(String regex, String node) {
        if (regex == null || regex.length() > 8192) throw new IllegalArgumentException(node + ": regex required, maximum 8192 characters");
        try { return Pattern.compile(regex); }
        catch (PatternSyntaxException e) { throw new IllegalArgumentException(node + ": invalid regex at " + e.getIndex()); }
    }
    static void replacement(Pattern pattern, String replacement, String node) {
        // Matcher verifies syntax/references on an unconditional match of the same group layout.
        int groupCount = pattern.matcher("").groupCount();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') { if (++i == replacement.length()) throw new IllegalArgumentException(node + ": dangling replacement escape"); }
            else if (c == '$') {
                if (++i == replacement.length()) throw new IllegalArgumentException(node + ": dangling replacement reference");
                c = replacement.charAt(i);
                if (c == '{') {
                    int end = replacement.indexOf('}', i);
                    if (end < 0 || !pattern.pattern().contains("(?<" + replacement.substring(i + 1, end) + ">")) throw new IllegalArgumentException(node + ": unknown named group");
                    i = end;
                } else if (c >= '0' && c <= '9') {
                    int group = c - '0';
                    if (group > groupCount) throw new IllegalArgumentException(node + ": unknown capture group");
                    while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                        int next = group * 10 + replacement.charAt(i + 1) - '0';
                        if (next > groupCount) break;
                        group = next; i++;
                    }
                } else throw new IllegalArgumentException(node + ": invalid replacement reference");
            }
        }
    }
}
