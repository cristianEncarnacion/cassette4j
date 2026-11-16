package io.github.cristianencarnacion.cassette4j.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One local server that speaks the wire format of OpenAI, Anthropic, Gemini and Ollama, so the
 * real client libraries can be tested against cassette4j without internet or API keys.
 *
 * <p>Every provider answers "Hola desde &lt;provider&gt;" ("Ho" + "la desde ..." when streaming)
 * and checks that the client sent its API key the way the real API expects it.</p>
 */
public final class FakeProviders implements AutoCloseable {

    public static final String OPENAI_KEY = "sk-it-openai-secret-0123456789abcdef";
    public static final String ANTHROPIC_KEY = "sk-ant-it-anthropic-secret-0123456789abcdef";
    public static final String GEMINI_KEY = "AIzaSyFakeGeminiKey0123456789abcdefghi";

    /** Upstream aliases registered with cassette4j for each provider. */
    public static final List<String> ALIASES = List.of("fakeopenai", "fakeanthropic", "fakegemini", "fakeollama");

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    public FakeProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        for (String alias : ALIASES) {
            System.setProperty("cassette4j.upstream." + alias, url);
        }
    }

    /** Number of calls that reached the fake APIs (replays must not increase it). */
    public int hits() {
        return hits.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean stream = body.contains("\"stream\":true");
            if (path.endsWith("/chat/completions")) {
                if (!("Bearer " + OPENAI_KEY).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    unauthorized(ex);
                    return;
                }
                hits.incrementAndGet();
                if (stream) {
                    sse(ex, openAiStream());
                } else {
                    json(ex, openAi());
                }
            } else if (path.endsWith("/messages")) {
                if (!ANTHROPIC_KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"))
                        || ex.getRequestHeaders().getFirst("anthropic-version") == null) {
                    unauthorized(ex);
                    return;
                }
                hits.incrementAndGet();
                if (stream) {
                    sse(ex, anthropicStream());
                } else {
                    json(ex, anthropic());
                }
            } else if (path.contains(":generateContent") || path.contains(":streamGenerateContent")) {
                if (!GEMINI_KEY.equals(ex.getRequestHeaders().getFirst("x-goog-api-key"))) {
                    unauthorized(ex);
                    return;
                }
                hits.incrementAndGet();
                if (path.contains(":streamGenerateContent")) {
                    sse(ex, geminiStream());
                } else {
                    json(ex, gemini("Hola desde Gemini", true));
                }
            } else if (path.endsWith("/api/chat")) {
                hits.incrementAndGet();
                if (body.contains("\"stream\":false")) {
                    json(ex, ollama("Hola desde Ollama", true));
                } else {
                    send(ex, 200, "application/x-ndjson",
                            ollama("Ho", false) + "\n" + ollama("la desde Ollama", false) + "\n" + ollama("", true) + "\n");
                }
            } else {
                send(ex, 404, "application/json", "{\"error\":{\"message\":\"unknown path " + path + "\"}}");
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ OpenAI

    private static String openAi() {
        return "{\"id\":\"chatcmpl-it\",\"object\":\"chat.completion\",\"created\":1763251200,"
                + "\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Hola desde OpenAI\",\"refusal\":null,\"annotations\":[]},\"logprobs\":null,"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4,"
                + "\"total_tokens\":13},\"system_fingerprint\":\"fp_it\"}";
    }

    private static List<String> openAiStream() {
        return List.of(
                openAiChunk("{\"role\":\"assistant\",\"content\":\"\"}", "null"),
                openAiChunk("{\"content\":\"Ho\"}", "null"),
                openAiChunk("{\"content\":\"la desde OpenAI\"}", "null"),
                openAiChunk("{}", "\"stop\""),
                "{\"id\":\"chatcmpl-it\",\"object\":\"chat.completion.chunk\",\"created\":1763251200,"
                        + "\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,"
                        + "\"completion_tokens\":4,\"total_tokens\":13}}",
                "[DONE]");
    }

    private static String openAiChunk(String delta, String finish) {
        return "{\"id\":\"chatcmpl-it\",\"object\":\"chat.completion.chunk\",\"created\":1763251200,"
                + "\"model\":\"gpt-4o-mini-2024-07-18\",\"system_fingerprint\":\"fp_it\",\"choices\":[{\"index\":0,"
                + "\"delta\":" + delta + ",\"logprobs\":null,\"finish_reason\":" + finish + "}]}";
    }

    // ------------------------------------------------------------------ Anthropic

    private static String anthropic() {
        return "{\"id\":\"msg_it\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hola desde Anthropic\"}],\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}";
    }

    private static List<String> anthropicStream() {
        return List.of(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_it\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\",\"content\":[],\"stop_reason\":null,"
                        + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "event: ping\ndata: {\"type\":\"ping\"}",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Ho\"}}",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"la desde Anthropic\"}}",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":4}}",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}");
    }

    // ------------------------------------------------------------------ Gemini

    private static String gemini(String text, boolean last) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}],\"role\":\"model\"}"
                + (last ? ",\"finishReason\":\"STOP\"" : "") + ",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4,\"totalTokenCount\":13},"
                + "\"modelVersion\":\"gemini-2.5-flash\",\"responseId\":\"resp-it\"}";
    }

    private static List<String> geminiStream() {
        return List.of(gemini("Ho", false), gemini("la desde Gemini", true));
    }

    // ------------------------------------------------------------------ Ollama

    private static String ollama(String text, boolean done) {
        return "{\"model\":\"llama3.2\",\"created_at\":\"2026-11-16T12:00:00.000000Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                + (done ? "\"done_reason\":\"stop\",\"done\":true,\"total_duration\":1000,\"load_duration\":10,"
                        + "\"prompt_eval_count\":9,\"prompt_eval_duration\":100,\"eval_count\":4,\"eval_duration\":200}"
                        : "\"done\":false}");
    }

    // ------------------------------------------------------------------ wire helpers

    private static void unauthorized(HttpExchange ex) throws IOException {
        send(ex, 401, "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}");
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "application/json", body);
    }

    /** Sends a server-sent event stream; plain JSON items get a {@code data: } prefix. */
    private static void sse(HttpExchange ex, List<String> events) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            for (String e : events) {
                String event = e.startsWith("event:") ? e : "data: " + e;
                os.write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
