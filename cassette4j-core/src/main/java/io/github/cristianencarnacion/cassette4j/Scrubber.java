package io.github.cristianencarnacion.cassette4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes secrets from everything that is written to a cassette.
 *
 * <p>Request headers (where API keys normally travel) are never stored at all. This class
 * additionally redacts key-shaped strings that show up in paths, query strings or bodies.</p>
 */
public final class Scrubber {

    public static final String REDACTED = "<redacted>";

    /** Extra patterns can be added with the system property {@code cassette4j.redact} (comma separated regexes). */
    public static final String EXTRA_PATTERNS_PROPERTY = "cassette4j.redact";

    private static final List<Pattern> DEFAULTS = List.of(
            Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}"),            // OpenAI, Anthropic (sk-ant-...), DeepSeek
            Pattern.compile("AIza[0-9A-Za-z_\\-]{30,}"),            // Google / Gemini
            Pattern.compile("gsk_[A-Za-z0-9]{20,}"),                // Groq
            Pattern.compile("xai-[A-Za-z0-9]{20,}"),                // xAI
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}"), // bearer tokens echoed in bodies
            Pattern.compile("(?i)([?&](?:key|api_key|apikey|api-key)=)[^&#\\s\"]+"));

    private Scrubber() {
    }

    static List<Pattern> patterns() {
        List<Pattern> all = new ArrayList<>(DEFAULTS);
        String extra = System.getProperty(EXTRA_PATTERNS_PROPERTY);
        if (extra != null && !extra.isBlank()) {
            for (String p : extra.split(",")) {
                if (!p.isBlank()) {
                    all.add(Pattern.compile(p.trim()));
                }
            }
        }
        return all;
    }

    public static String scrub(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Pattern p : patterns()) {
            if (p.pattern().contains("[?&]")) {
                out = p.matcher(out).replaceAll("$1" + REDACTED);
            } else {
                out = p.matcher(out).replaceAll(REDACTED);
            }
        }
        return out;
    }

    /** Scrubs every string inside a parsed JSON value. */
    @SuppressWarnings("unchecked")
    public static Object scrubJson(Object value) {
        if (value instanceof String s) {
            return scrub(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, scrubJson(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(scrubJson(o));
            }
            return out;
        }
        return value;
    }
}
