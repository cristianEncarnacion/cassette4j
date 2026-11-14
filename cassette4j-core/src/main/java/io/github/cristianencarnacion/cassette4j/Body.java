package io.github.cristianencarnacion.cassette4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request or response body as stored in a cassette.
 *
 * <p>JSON bodies are stored as JSON (readable and diff-friendly), server-sent event streams as a
 * list of raw events, anything else as plain text.</p>
 */
public final class Body {

    private final Object json;
    private final boolean isJson;
    private final List<String> events;
    private final String text;

    private Body(Object json, boolean isJson, List<String> events, String text) {
        this.json = json;
        this.isJson = isJson;
        this.events = events;
        this.text = text;
    }

    public static Body empty() {
        return new Body(null, false, null, "");
    }

    /** Builds a body from raw text, choosing the most readable representation. Secrets are scrubbed. */
    public static Body of(String contentType, String raw) {
        if (raw == null || raw.isEmpty()) {
            return empty();
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("text/event-stream")) {
            return new Body(null, false, splitEvents(Scrubber.scrub(raw)), null);
        }
        if (ct.contains("json") || ct.isEmpty()) {
            if (Json.isJson(raw)) {
                return new Body(Scrubber.scrubJson(Json.parse(raw.strip())), true, null, null);
            }
        }
        return new Body(null, false, null, Scrubber.scrub(raw));
    }

    static List<String> splitEvents(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        List<String> out = new ArrayList<>();
        for (String part : normalized.split("\n\n")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    public boolean isJson() {
        return isJson;
    }

    public Object json() {
        return json;
    }

    public List<String> events() {
        return events;
    }

    /** Text sent over the wire when this body is replayed. */
    public String toRaw() {
        if (isJson) {
            return Json.write(json);
        }
        if (events != null) {
            StringBuilder sb = new StringBuilder();
            for (String e : events) {
                sb.append(e).append("\n\n");
            }
            return sb.toString();
        }
        return text == null ? "" : text;
    }

    /** Representation used to compare requests. */
    public String matchKey(java.util.Set<String> ignoreFields) {
        if (isJson) {
            return Json.canonical(withoutFields(json, ignoreFields));
        }
        return toRaw();
    }

    @SuppressWarnings("unchecked")
    static Object withoutFields(Object value, java.util.Set<String> ignore) {
        if (ignore == null || ignore.isEmpty()) {
            return value;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> {
                if (!ignore.contains(k)) {
                    out.put(k, withoutFields(v, ignore));
                }
            });
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(withoutFields(o, ignore));
            }
            return out;
        }
        return value;
    }

    /** Writes the body fields into a cassette entry. */
    void writeInto(Map<String, Object> target) {
        if (isJson) {
            target.put("json", json);
        } else if (events != null) {
            target.put("events", new ArrayList<Object>(events));
        } else if (text != null && !text.isEmpty()) {
            target.put("text", text);
        }
    }

    @SuppressWarnings("unchecked")
    static Body readFrom(Map<String, Object> source) {
        if (source.containsKey("json")) {
            return new Body(source.get("json"), true, null, null);
        }
        if (source.containsKey("events")) {
            List<String> ev = new ArrayList<>();
            for (Object o : (List<Object>) source.get("events")) {
                ev.add(String.valueOf(o));
            }
            return new Body(null, false, ev, null);
        }
        Object t = source.get("text");
        return t == null ? empty() : new Body(null, false, null, String.valueOf(t));
    }
}
