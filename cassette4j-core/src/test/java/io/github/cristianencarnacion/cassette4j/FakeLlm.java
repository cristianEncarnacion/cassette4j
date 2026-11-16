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

    /** Fake MP3 bytes: every byte value, so any text conversion would corrupt it. */
    static final byte[] AUDIO = audio();

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
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/audio/speech")) {
            sendBytes(ex, 200, "audio/mpeg", AUDIO);
            return;
        }
        if (path.endsWith("/audio/transcriptions")) {
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.startsWith("multipart/form-data") || !body.contains("name=\"file\"")) {
                send(ex, 400, "application/json", "{\"error\":{\"message\":\"expected a multipart upload\"}}");
                return;
            }
            send(ex, 200, "application/json", "{\"text\":\"transcribed " + n + "\"}");
            return;
        }
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

    private static byte[] audio() {
        byte[] b = new byte[1024];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i * 7);
        }
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xFB;
        return b;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        sendBytes(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int status, String type, byte[] b) throws IOException {
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

    static HttpResponse<byte[]> speech(String text) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(Cassette4j.baseUrl() + "/fake/v1/audio/speech"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"tts-1\",\"voice\":\"alloy\",\"input\":\"" + text + "\"}"))
                .build();
        return CLIENT.send(r, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Uploads audio like the OpenAI SDKs do: multipart with a new random boundary every time. */
    static HttpResponse<String> transcribe(byte[] audio) throws Exception {
        String boundary = "----JavaBoundary" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.mp3\"\r\n"
                + "Content-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest r = HttpRequest.newBuilder(URI.create(Cassette4j.baseUrl() + "/fake/v1/audio/transcriptions"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
    }

    static String prompt(String text) {
        return "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}";
    }
}
