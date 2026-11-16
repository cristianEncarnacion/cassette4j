package io.github.cristianencarnacion.cassette4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP server that sits between the code under test and the real LLM API.
 *
 * <p>It listens on the loopback interface only. Requests under {@code /__cassette4j/} control
 * which cassette is inserted; every other request is answered from the cassette or forwarded
 * to the real API and recorded, depending on the mode.</p>
 *
 * <p>Control goes over HTTP on purpose: frameworks such as Quarkus load tests in their own
 * class loader, and an HTTP control channel works no matter which class loader inserts the
 * cassette.</p>
 */
final class ProxyServer {

    static final String ADMIN_PREFIX = "/__cassette4j/";

    /** Request headers that must not be forwarded (hop-by-hop, restricted, or encoding). */
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "host", "connection", "content-length", "expect", "upgrade", "keep-alive",
            "transfer-encoding", "te", "trailer", "proxy-authorization", "proxy-connection",
            "accept-encoding", "http2-settings");

    /** Statuses that are passed through but never recorded. */
    private static final Set<Integer> NOT_RECORDED = Set.of(401, 403, 407, 429);

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private volatile ActiveCassette active;

    private ProxyServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.client = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static ProxyServer start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "cassette4j-proxy");
            t.setDaemon(true);
            return t;
        });
        ProxyServer proxy = new ProxyServer(http, pool);
        http.createContext("/", proxy::handle);
        http.setExecutor(pool);
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop, "cassette4j-shutdown"));
        return proxy;
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (path.startsWith(ADMIN_PREFIX)) {
                handleAdmin(ex, path.substring(ADMIN_PREFIX.length()));
            } else {
                handleProxy(ex);
            }
        } catch (RuntimeException | IOException e) {
            try {
                respondError(ex, 502, "cassette4j_error", "cassette4j proxy error: " + e);
            } catch (IOException | RuntimeException ignored) {
                // response already started; nothing else we can do
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ admin channel

    @SuppressWarnings("unchecked")
    private void handleAdmin(HttpExchange ex, String action) throws IOException {
        switch (action) {
            case "health" -> respond(ex, 200, "text/plain", "cassette4j ok");
            case "insert" -> {
                Map<String, Object> req = (Map<String, Object>) Json.parse(
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                List<String> ignore = strings(req.get("ignoreFields"));
                List<String> match = strings(req.get("matchFields"));
                ActiveCassette previous = active;
                if (previous != null) {
                    previous.save();
                }
                active = new ActiveCassette(
                        Path.of(String.valueOf(req.get("file"))),
                        Mode.valueOf(String.valueOf(req.get("mode"))),
                        MatchRules.of(ignore, match),
                        req.get("upstream") == null ? "" : String.valueOf(req.get("upstream")));
                respond(ex, 200, "application/json", "{\"ok\":true}");
            }
            case "eject" -> {
                ActiveCassette current = active;
                active = null;
                if (current == null) {
                    respond(ex, 200, "application/json", "{\"ejected\":false}");
                    return;
                }
                current.save();
                respond(ex, 200, "application/json", Json.write(current.resultMap()));
            }
            default -> respondError(ex, 404, "cassette4j_error", "Unknown control action: " + action);
        }
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    // ------------------------------------------------------------------ proxy

    private void handleProxy(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        URI uri = ex.getRequestURI();
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        byte[] requestBytes = ex.getRequestBody().readAllBytes();
        String requestType = ex.getRequestHeaders().getFirst("Content-Type");

        ActiveCassette c = active;
        if (c == null) {
            respondError(ex, 404, "cassette4j_no_cassette",
                    "cassette4j: an LLM call reached the proxy but no cassette is inserted. "
                            + "Annotate the test with @LlmCassette or wrap the code in Cassette4j.insert(...).");
            return;
        }

        String storedPath = Scrubber.scrub(pathAndQuery);
        Body requestBody = Body.of(requestType, new String(requestBytes, StandardCharsets.UTF_8));
        String key = method + " " + storedPath + "\n" + requestBody.matchKey(c.rules);

        if (c.mode == Mode.AUTO || c.mode == Mode.REPLAY) {
            Interaction hit = c.take(key);
            if (hit != null) {
                respond(ex, hit.status(), hit.contentType(), hit.responseBody().toRaw());
                return;
            }
            if (c.mode == Mode.REPLAY) {
                String detail = c.describeMiss(method, storedPath, requestBody);
                c.miss(detail);
                respondError(ex, 404, "cassette4j_miss", "cassette4j: no recorded interaction for this call. " + detail);
                return;
            }
        }

        Upstreams.Target target = Upstreams.resolve(pathAndQuery, c.upstream);
        if (target == null) {
            String msg = "No real API is known for path '" + storedPath + "'. Start the base URL with an alias "
                    + "such as /openai or /anthropic, set @LlmCassette(upstream = \"https://...\") or "
                    + "-D" + Upstreams.DEFAULT_PROPERTY + "=https://...";
            c.miss(msg);
            respondError(ex, 404, "cassette4j_no_upstream", "cassette4j: " + msg);
            return;
        }

        HttpResponse<byte[]> upstream;
        try {
            upstream = forward(ex, method, target.url(), requestBytes);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + target.url(), ie);
        } catch (IOException ioe) {
            c.miss("Could not reach " + Scrubber.scrub(target.url()) + ": " + ioe);
            respondError(ex, 502, "cassette4j_upstream_unreachable",
                    "cassette4j: could not reach the real API (" + ioe.getMessage() + ")");
            return;
        }

        String responseType = upstream.headers().firstValue("Content-Type").orElse(null);
        String responseText = new String(upstream.body(), StandardCharsets.UTF_8);
        int status = upstream.statusCode();

        if (c.mode != Mode.PASSTHROUGH) {
            if (status >= 500 || NOT_RECORDED.contains(status)) {
                c.warn("Not recorded: " + method + " " + storedPath + " returned " + status
                        + (status == 401 || status == 403 ? " (check your API key)" : ""));
            } else {
                c.record(key, new Interaction(method, storedPath, requestBody, status, responseType,
                        Body.of(responseType, responseText)));
            }
        }
        respond(ex, status, responseType, responseText);
    }

    private HttpResponse<byte[]> forward(HttpExchange ex, String method, String url, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        ex.getRequestHeaders().forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String v : values) {
                    b.header(name, v);
                }
            }
        });
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // ------------------------------------------------------------------ responses

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.getResponseHeaders().set("X-Cassette4j", "1");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** Errors use the OpenAI/Anthropic error shape so client SDKs surface the message. */
    private static void respondError(HttpExchange ex, int status, String type, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", type);
        err.put("message", message);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "error");
        root.put("error", err);
        respond(ex, status, "application/json", Json.write(root));
    }

    // ------------------------------------------------------------------ cassette state

    /** State of the cassette currently inserted. All access is synchronized on the instance. */
    static final class ActiveCassette {
        final Path file;
        final Mode mode;
        final MatchRules rules;
        final String upstream;

        private final List<Interaction> loaded;
        private final Map<String, List<Interaction>> byKey = new HashMap<>();
        private final Map<String, Integer> nextIndex = new HashMap<>();
        private final List<Interaction> newlyRecorded = new ArrayList<>();
        private final List<String> misses = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int replayed;
        private boolean saved;

        ActiveCassette(Path file, Mode mode, MatchRules rules, String upstream) {
            this.file = file;
            this.mode = mode;
            this.rules = rules;
            this.upstream = upstream;
            this.loaded = mode == Mode.RECORD || mode == Mode.PASSTHROUGH
                    ? List.of()
                    : Cassette.load(file).interactions();
            for (Interaction i : loaded) {
                byKey.computeIfAbsent(i.matchKey(rules), k -> new ArrayList<>()).add(i);
            }
        }

        /**
         * Returns the next recorded answer for this request. Identical requests get the recorded
         * answers in order; once they run out the last one is repeated.
         */
        synchronized Interaction take(String key) {
            List<Interaction> candidates = byKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            int idx = nextIndex.getOrDefault(key, 0);
            if (idx >= candidates.size()) {
                if (mode == Mode.AUTO) {
                    return null; // record one more answer instead of repeating
                }
                idx = candidates.size() - 1;
            } else {
                nextIndex.put(key, idx + 1);
            }
            replayed++;
            return candidates.get(idx);
        }

        synchronized void record(String key, Interaction interaction) {
            newlyRecorded.add(interaction);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(interaction);
            nextIndex.merge(key, 1, Integer::sum);
        }

        synchronized void miss(String detail) {
            misses.add(detail);
        }

        synchronized void warn(String detail) {
            warnings.add(detail);
        }

        /** Explains why a request did not match, pointing at the closest recorded request. */
        synchronized String describeMiss(String method, String path, Body body) {
            String wanted = body.matchKey(rules);
            Interaction closest = null;
            int bestPrefix = -1;
            for (Interaction i : loaded) {
                if (!i.method().equals(method) || !i.path().equals(path)) {
                    continue;
                }
                String have = i.requestBody().matchKey(rules);
                int p = commonPrefix(wanted, have);
                if (p > bestPrefix) {
                    bestPrefix = p;
                    closest = i;
                }
            }
            StringBuilder sb = new StringBuilder(method).append(' ').append(path);
            if (closest == null) {
                sb.append(loaded.isEmpty()
                        ? " - the cassette is empty or does not exist yet."
                        : " - no recorded call uses this method and path.");
                sb.append("\n    request:  ").append(wanted.length() > 4000 ? wanted.substring(0, 4000) + "..." : wanted);
                return sb.toString();
            }
            String have = closest.requestBody().matchKey(rules);
            sb.append(" - closest recorded request differs at character ").append(bestPrefix).append(":\n")
                    .append("    recorded: ").append(window(have, bestPrefix)).append('\n')
                    .append("    actual:   ").append(window(wanted, bestPrefix));
            return sb.toString();
        }

        private static int commonPrefix(String a, String b) {
            int n = Math.min(a.length(), b.length());
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i;
        }

        private static String window(String s, int at) {
            int from = Math.max(0, at - 40);
            int to = Math.min(s.length(), at + 60);
            return (from > 0 ? "..." : "") + s.substring(from, to) + (to < s.length() ? "..." : "");
        }

        /** Writes the cassette when something new was recorded. */
        synchronized void save() {
            if (saved || newlyRecorded.isEmpty() || mode == Mode.PASSTHROUGH) {
                saved = true;
                return;
            }
            List<Interaction> all = new ArrayList<>(loaded);
            all.addAll(newlyRecorded);
            new Cassette(all).save(file);
            saved = true;
        }

        synchronized Map<String, Object> resultMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", file.toString());
            m.put("mode", mode.name());
            m.put("replayed", new Json.Num(Integer.toString(replayed)));
            m.put("recorded", new Json.Num(Integer.toString(newlyRecorded.size())));
            m.put("misses", new ArrayList<Object>(misses));
            m.put("warnings", new ArrayList<Object>(warnings));
            return m;
        }
    }
}
