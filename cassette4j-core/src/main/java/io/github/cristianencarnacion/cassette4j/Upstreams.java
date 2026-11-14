package io.github.cristianencarnacion.cassette4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the first path segment of a proxied request to the real API it stands for.
 *
 * <p>{@code http://127.0.0.1:PORT/openai/v1/chat/completions} is forwarded to
 * {@code https://api.openai.com/v1/chat/completions}. Aliases can be added or overridden with
 * system properties: {@code -Dcassette4j.upstream.myllm=https://llm.mycompany.com}.</p>
 */
public final class Upstreams {

    /** Prefix of the system properties that define or override aliases. */
    public static final String ALIAS_PROPERTY_PREFIX = "cassette4j.upstream.";

    /** Upstream used for requests whose first path segment is not an alias. */
    public static final String DEFAULT_PROPERTY = "cassette4j.upstream";

    private static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("openai", "https://api.openai.com");
        BUILT_IN.put("anthropic", "https://api.anthropic.com");
        BUILT_IN.put("gemini", "https://generativelanguage.googleapis.com");
        BUILT_IN.put("mistral", "https://api.mistral.ai");
        BUILT_IN.put("groq", "https://api.groq.com");
        BUILT_IN.put("deepseek", "https://api.deepseek.com");
        BUILT_IN.put("xai", "https://api.x.ai");
        BUILT_IN.put("openrouter", "https://openrouter.ai");
        BUILT_IN.put("ollama", "http://localhost:11434");
    }

    /** Where a proxied request must go. */
    public record Target(String alias, String url) {
    }

    private Upstreams() {
    }

    /** Built-in aliases plus the ones defined with system properties. */
    public static Map<String, String> aliases() {
        Map<String, String> all = new LinkedHashMap<>(BUILT_IN);
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(ALIAS_PROPERTY_PREFIX) && name.length() > ALIAS_PROPERTY_PREFIX.length()) {
                all.put(name.substring(ALIAS_PROPERTY_PREFIX.length()), System.getProperty(name));
            }
        }
        return all;
    }

    /**
     * Resolves the real URL for a proxied path (path plus optional query string).
     *
     * @return the target, or {@code null} when no upstream is known for this path
     */
    public static Target resolve(String pathAndQuery, String cassetteUpstream) {
        String p = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;
        int end = p.length();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        String first = p.substring(0, end);
        String base = aliases().get(first);
        if (base != null) {
            String rest = p.substring(end);
            if (rest.isEmpty() || rest.startsWith("?")) {
                rest = "/" + rest;
            }
            return new Target(first, trimSlash(base) + rest);
        }
        String fallback = cassetteUpstream;
        if (fallback == null || fallback.isBlank()) {
            fallback = System.getProperty(DEFAULT_PROPERTY);
        }
        if (fallback == null || fallback.isBlank()) {
            return null;
        }
        return new Target("", trimSlash(fallback) + "/" + p);
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
