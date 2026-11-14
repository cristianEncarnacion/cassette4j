package io.github.cristianencarnacion.cassette4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal, dependency-free JSON parser and writer.
 *
 * <p>Values are represented as {@link Map} (insertion ordered), {@link List}, {@link String},
 * {@link Boolean}, {@link Num} (numbers keep their original text so they are written back
 * byte-for-byte) and {@code null}.</p>
 */
public final class Json {

    /** A JSON number that keeps its original lexical form. */
    public record Num(String raw) {
        @Override
        public String toString() {
            return raw;
        }
    }

    private Json() {
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("Unexpected trailing content");
        }
        return value;
    }

    /** Returns {@code true} when the text is a JSON object or array. */
    public static boolean isJson(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return false;
        }
        try {
            parse(t);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw error("Expected '" + word + "'");
            }
            pos += word.length();
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("Expected object key");
                }
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("Expected ':'");
                }
                pos++;
                skipWs();
                map.put(key, readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw error("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw error("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("Bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Bad escape '\\" + e + "'");
                    }
                } else {
                    if (c < 0x20) {
                        throw error("Control character in string");
                    }
                    sb.append(c);
                }
            }
        }

        Num readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            boolean digits = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9')) {
                    digits = true;
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (!digits) {
                throw error("Invalid number");
            }
            return new Num(s.substring(start, pos));
        }
    }

    // ------------------------------------------------------------------ writing

    /** Compact serialization preserving key order. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value, false, -1, 0);
        return sb.toString();
    }

    /** Pretty serialization (2 spaces) preserving key order. */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value, false, 2, 0);
        return sb.toString();
    }

    /** Compact serialization with object keys sorted, used for request matching. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value, true, -1, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeTo(StringBuilder sb, Object v, boolean sorted, int indent, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean || v instanceof Num) {
            sb.append(v);
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            if (sorted) {
                map = new TreeMap<>(map);
            }
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                newline(sb, indent, level + 1);
                quote(sb, e.getKey());
                sb.append(indent >= 0 ? ": " : ":");
                writeTo(sb, e.getValue(), sorted, indent, level + 1);
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                newline(sb, indent, level + 1);
                writeTo(sb, list.get(i), sorted, indent, level + 1);
                if (i < list.size() - 1) {
                    sb.append(',');
                }
            }
            newline(sb, indent, level);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        sb.append(" ".repeat(indent * level));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
