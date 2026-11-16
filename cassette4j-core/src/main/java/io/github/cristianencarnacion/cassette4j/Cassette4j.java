package io.github.cristianencarnacion.cassette4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point of cassette4j.
 *
 * <pre>{@code
 * // Point your LLM client at the proxy instead of the real API
 * OpenAiChatModel model = OpenAiChatModel.builder()
 *         .baseUrl(Cassette4j.baseUrl("openai") + "/v1")
 *         .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", "replay-only"))
 *         .modelName("gpt-4o-mini")
 *         .build();
 *
 * try (Session s = Cassette4j.insert(Path.of("src/test/resources/cassettes/greeting.json"))) {
 *     String answer = model.chat("Say hi");
 * }
 * }</pre>
 *
 * <p>With JUnit 5, use {@code @LlmCassette} from the {@code cassette4j-junit5} module instead of
 * calling {@link #insert} by hand.</p>
 */
public final class Cassette4j {

    /** JVM-wide system property holding the port of the running proxy (set by cassette4j). */
    public static final String PORT_PROPERTY = "cassette4j.internal.port";

    /**
     * JVM-wide system property holding the random secret of the running proxy (set by cassette4j).
     * Control requests must present it, so other programs on the machine cannot swap cassettes.
     */
    public static final String TOKEN_PROPERTY = "cassette4j.internal.token";

    /** Optional fixed port for the proxy (default: a free port). */
    public static final String FIXED_PORT_PROPERTY = "cassette4j.port";

    /** Directory for cassettes (default {@code src/test/resources/cassettes}). */
    public static final String DIR_PROPERTY = "cassette4j.dir";

    private static ProxyServer localServer;

    private Cassette4j() {
    }

    /**
     * Starts the proxy if no proxy is running in this JVM yet and returns its port.
     *
     * <p>The port is published as a system property so code loaded by any class loader
     * (Quarkus, Spring Boot devtools, ...) talks to the same proxy.</p>
     */
    public static int port() {
        Object lock = System.getProperties();
        synchronized (lock) {
            String existing = System.getProperty(PORT_PROPERTY);
            if (existing != null && healthy(Integer.parseInt(existing))) {
                return Integer.parseInt(existing);
            }
            try {
                int fixed = Integer.parseInt(System.getProperty(FIXED_PORT_PROPERTY, "0"));
                localServer = ProxyServer.start(fixed);
                System.setProperty(TOKEN_PROPERTY, localServer.token());
                System.setProperty(PORT_PROPERTY, Integer.toString(localServer.port()));
                return localServer.port();
            } catch (IOException e) {
                throw new UncheckedIOException("cassette4j could not start its local proxy", e);
            }
        }
    }

    /** Base URL of the proxy, e.g. {@code http://127.0.0.1:51234}. */
    public static String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /**
     * Base URL that stands for a real API, e.g. {@code baseUrl("openai")} for
     * {@code https://api.openai.com}. Append the API version yourself when your client expects it
     * ({@code baseUrl("openai") + "/v1"}).
     */
    public static String baseUrl(String alias) {
        return baseUrl() + "/" + alias;
    }

    /** Default folder for cassettes. */
    public static Path cassetteDir() {
        return Path.of(System.getProperty(DIR_PROPERTY, "src/test/resources/cassettes"));
    }

    /** Inserts a cassette with default options. */
    public static Session insert(Path file) {
        return insert(file, Options.defaults());
    }

    /** Inserts a cassette. Close the returned session to eject it and save new recordings. */
    public static Session insert(Path file, Options options) {
        Mode mode = Mode.resolve(options.mode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", file.toAbsolutePath().normalize().toString());
        body.put("mode", mode.name());
        body.put("ignoreFields", new ArrayList<Object>(options.ignoreFields()));
        body.put("matchFields", new ArrayList<Object>(options.matchFields()));
        body.put("upstream", options.upstream());
        control("insert", Json.write(body));
        return new Session(file, mode);
    }

    @SuppressWarnings("unchecked")
    static Result eject() {
        Map<String, Object> m = (Map<String, Object>) Json.parse(control("eject", "{}"));
        if (!Boolean.FALSE.equals(m.get("ejected")) && m.containsKey("file")) {
            return new Result(
                    String.valueOf(m.get("file")),
                    Mode.valueOf(String.valueOf(m.get("mode"))),
                    Integer.parseInt(String.valueOf(m.get("replayed"))),
                    Integer.parseInt(String.valueOf(m.get("recorded"))),
                    strings(m.get("misses")),
                    strings(m.get("warnings")));
        }
        return new Result("", Mode.DEFAULT, 0, 0, List.of(), List.of());
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    // ------------------------------------------------------------------ control channel

    private static boolean healthy(int port) {
        try {
            HttpURLConnection c = open(port, "health", "GET");
            c.setConnectTimeout(500);
            c.setReadTimeout(1000);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static String control(String action, String json) {
        int port = port();
        try {
            HttpURLConnection c = open(port, action, "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty(ProxyServer.TOKEN_HEADER, System.getProperty(TOKEN_PROPERTY, ""));
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IllegalStateException("cassette4j control '" + action + "' failed: " + text);
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("cassette4j control '" + action + "' failed", e);
        }
    }

    private static HttpURLConnection open(int port, String action, String method) throws IOException {
        URL url = java.net.URI.create("http://127.0.0.1:" + port + ProxyServer.ADMIN_PREFIX + action).toURL();
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(30000);
        return c;
    }
}
