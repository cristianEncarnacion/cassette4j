package io.github.cristianencarnacion.cassette4j;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request or response body as stored in a cassette.
 *
 * <p>JSON bodies are stored as JSON (readable and diff-friendly), server-sent event streams as a
 * list of raw events, other text as plain text, and binary content (audio, images, file uploads)
 * as base64 so it is replayed byte for byte.</p>
 */
public final class Body {

    /** Placeholder that replaces the random multipart boundary so uploads match across runs. */
    static final String BOUNDARY_PLACEHOLDER = "cassette4j-boundary";

    private final Object json;
    private final boolean isJson;
    private final List<String> events;
    private final String text;
    private final byte[] binary;

    private Body(Object json, boolean isJson, List<String> events, String text, byte[] binary) {
        this.json = json;
        this.isJson = isJson;
        this.events = events;
        this.text = text;
        this.binary = binary;
    }

    public static Body empty() {
        return new Body(null, false, null, "", null);
    }

    /** Builds a body from text, choosing the most readable representation. Secrets are scrubbed. */
    public static Body of(String contentType, String raw) {
        return of(contentType, raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a body from raw bytes. Text formats are stored readably with secrets scrubbed;
     * binary content is stored as base64 and never altered.
     */
    public static Body of(String contentType, byte[] raw) {
        if (raw == null || raw.length == 0) {
            return empty();
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("multipart/")) {
            raw = normalizeBoundary(contentType, raw);
        }
        String decoded = isTextual(ct) || ct.isEmpty() || ct.startsWith("multipart/") ? utf8(raw) : null;
        if (decoded == null) {
            return new Body(null, false, null, null, raw.clone());
        }
        if (ct.contains("text/event-stream")) {
            return new Body(null, false, splitEvents(Scrubber.scrub(decoded)), null, null);
        }
        if ((ct.contains("json") || ct.isEmpty()) && !ct.contains("ndjson") && Json.isJson(decoded)) {
            return new Body(Scrubber.scrubJson(Json.parse(decoded.strip())), true, null, null, null);
        }
        return new Body(null, false, null, Scrubber.scrub(decoded), null);
    }

    private static boolean isTextual(String ct) {
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("xml")
                || ct.contains("x-www-form-urlencoded") || ct.contains("javascript") || ct.contains("yaml");
    }

    /** Decodes strict UTF-8, or returns {@code null} when the bytes are not valid UTF-8 text. */
    private static String utf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Multipart clients pick a random boundary per request; replace it so requests compare equal. */
    static byte[] normalizeBoundary(String contentType, byte[] raw) {
        String boundary = boundaryOf(contentType);
        if (boundary == null || boundary.isEmpty()) {
            return raw;
        }
        byte[] needle = boundary.getBytes(StandardCharsets.ISO_8859_1);
        byte[] replacement = BOUNDARY_PLACEHOLDER.getBytes(StandardCharsets.ISO_8859_1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length);
        int i = 0;
        while (i < raw.length) {
            if (startsWith(raw, i, needle)) {
                out.write(replacement, 0, replacement.length);
                i += needle.length;
            } else {
                out.write(raw[i++]);
            }
        }
        return out.toByteArray();
    }

    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).strip();
                if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] haystack, int at, byte[] needle) {
        if (at + needle.length > haystack.length) {
            return false;
        }
        for (int j = 0; j < needle.length; j++) {
            if (haystack[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
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

    /** {@code true} when the body is binary content stored as base64. */
    public boolean isBinary() {
        return binary != null;
    }

    /** Text sent over the wire when this body is replayed (binary bodies: use {@link #toBytes()}). */
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
        if (binary != null) {
            return new String(binary, StandardCharsets.ISO_8859_1);
        }
        return text == null ? "" : text;
    }

    /** Bytes sent over the wire when this body is replayed. */
    public byte[] toBytes() {
        return binary != null ? binary.clone() : toRaw().getBytes(StandardCharsets.UTF_8);
    }

    /** Representation used to compare requests. */
    String matchKey(MatchRules rules) {
        if (isJson) {
            return Json.canonical(rules.apply(json));
        }
        if (binary != null) {
            return "binary sha-256 " + sha256(binary) + " (" + binary.length + " bytes)";
        }
        return toRaw();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        } else if (binary != null) {
            target.put("base64", Base64.getEncoder().encodeToString(binary));
        } else if (text != null && !text.isEmpty()) {
            target.put("text", text);
        }
    }

    @SuppressWarnings("unchecked")
    static Body readFrom(Map<String, Object> source) {
        if (source.containsKey("json")) {
            return new Body(source.get("json"), true, null, null, null);
        }
        if (source.containsKey("events")) {
            List<String> ev = new ArrayList<>();
            for (Object o : (List<Object>) source.get("events")) {
                ev.add(String.valueOf(o));
            }
            return new Body(null, false, ev, null, null);
        }
        if (source.containsKey("base64")) {
            return new Body(null, false, null, null, Base64.getDecoder().decode(String.valueOf(source.get("base64"))));
        }
        Object t = source.get("text");
        return t == null ? empty() : new Body(null, false, null, String.valueOf(t), null);
    }
}
