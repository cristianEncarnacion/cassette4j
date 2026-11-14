package io.github.cristianencarnacion.cassette4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake OpenAI-compatible API used by the tests, so no test ever touches the internet.
 * It is registered as the upstream alias {@code fake}.
 */
final class FakeLlm implements AutoCloseable {

    static final String API_KEY = "sk-test-secret-key-1234567890abcdef";

    final AtomicInteger hits = new AtomicInteger();
    private final HttpServer server;

    FakeLlm() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty(Upstreams.ALIAS_PROPERTY_PREFIX + "fake", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.equals("Bearer " + API_KEY)) {
            send(ex, 401, "application/json", "{\"error\":{\"message\":\"Incorrect API key\"}}");
            return;
        }
        int n = hits.incrementAndGet();
        if (body.contains("\"stream\":true")) {
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Ho\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"la\"}}]}\n\n"
                    + "data: [DONE]\n\n";
            send(ex, 200, "text/event-stream", sse);
            return;
        }
        String json = "{\"id\":\"chatcmpl-" + n + "\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"answer " + n + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15},"
                + "\"debug_echo\":\"" + API_KEY + "\",\"temperature_used\":0.70}";
        send(ex, 200, "application/json", json);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ client helpers

    static final HttpClient CLIENT = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    static HttpResponse<String> chat(String jsonBody) throws Exception {
        return post("/fake/v1/chat/completions", jsonBody, true);
    }

    static HttpResponse<String> post(String path, String jsonBody, boolean withKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(Cassette4j.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (withKey) {
            b.header("Authorization", "Bearer " + API_KEY);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String prompt(String text) {
        return "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}";
    }
}
