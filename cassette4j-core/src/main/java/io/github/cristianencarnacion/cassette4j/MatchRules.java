package io.github.cristianencarnacion.cassette4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How request bodies are compared.
 *
 * @param ignoreFields JSON fields removed at any depth before comparing
 * @param matchFields  when not empty, only these top-level JSON fields are compared
 *                     (e.g. {@code model} and {@code messages})
 */
record MatchRules(Set<String> ignoreFields, Set<String> matchFields) {

    static final MatchRules NONE = new MatchRules(Set.of(), Set.of());

    MatchRules {
        ignoreFields = Set.copyOf(ignoreFields);
        matchFields = Set.copyOf(matchFields);
    }

    static MatchRules of(List<String> ignore, List<String> match) {
        return new MatchRules(Set.copyOf(ignore), Set.copyOf(match));
    }

    @SuppressWarnings("unchecked")
    Object apply(Object json) {
        Object value = json;
        if (!matchFields.isEmpty() && value instanceof Map<?, ?> m) {
            Map<String, Object> kept = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> {
                if (matchFields.contains(k)) {
                    kept.put(k, v);
                }
            });
            value = kept;
        }
        return Body.withoutFields(value, ignoreFields);
    }
}
