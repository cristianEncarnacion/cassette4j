package io.github.cristianencarnacion.cassette4j.it;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.cristianencarnacion.cassette4j.Cassette4j;
import io.github.cristianencarnacion.cassette4j.Mode;
import io.github.cristianencarnacion.cassette4j.Options;
import io.github.cristianencarnacion.cassette4j.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records against the real OpenAI API, then replays with a fake key and no network access to
 * OpenAI, and checks the answer is identical. Costs a fraction of a cent per run.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiLiveTest {

    private static final String KEY = System.getenv("OPENAI_API_KEY");
    private static final String PROMPT = "Reply with exactly one word: pong";
    private static final Path DIR = Path.of("target", "live-cassettes");

    private static OpenAiChatModel chatModel(String key) {
        return OpenAiChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("openai") + "/v1")
                .apiKey(key).modelName("gpt-4o-mini").temperature(0.0).maxTokens(10).maxRetries(0)
                .build();
    }

    private static OpenAiStreamingChatModel streamingModel(String key) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("openai") + "/v1")
                .apiKey(key).modelName("gpt-4o-mini").temperature(0.0).maxTokens(10)
                .build();
    }

    @Test
    void chat() throws Exception {
        String answer = roundTrip("chat.json",
                () -> chatModel(KEY).chat(PROMPT),
                () -> chatModel("replay-only").chat(PROMPT));
        System.out.println("[live] chat answer: " + answer);
        assertTrue(answer.toLowerCase().contains("pong"), answer);
    }

    @Test
    void streaming() throws Exception {
        String answer = roundTrip("streaming.json",
                () -> stream(streamingModel(KEY)),
                () -> stream(streamingModel("replay-only")));
        System.out.println("[live] streamed answer: " + answer);
        assertTrue(answer.toLowerCase().contains("pong"), answer);
    }

    @Test
    void textToSpeechAudio() throws Exception {
        Path file = DIR.resolve("speech.json");
        byte[] recorded;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.RECORD))) {
            recorded = speech(KEY);
            assertEquals(1, s.eject().recorded());
        }
        assertTrue(recorded.length > 1000, "audio too small: " + recorded.length + " bytes");
        String cassette = Files.readString(file);
        assertTrue(cassette.contains("\"base64\""), "audio should be stored as base64");
        assertFalse(cassette.contains(KEY), "API key leaked into the cassette");

        byte[] replayed;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            replayed = speech("replay-only");
            assertEquals(1, s.eject().replayed());
        }
        assertArrayEquals(recorded, replayed);
        System.out.println("[live] audio bytes recorded and replayed identically: " + recorded.length);
    }

    // ------------------------------------------------------------------ helpers

    private static String roundTrip(String name, Supplier<String> live, Supplier<String> replay) throws Exception {
        Path file = DIR.resolve(name);
        String recorded;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.RECORD))) {
            recorded = live.get();
            assertEquals(1, s.eject().recorded());
        }
        assertFalse(Files.readString(file).contains(KEY), "API key leaked into the cassette");

        String replayed;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            replayed = replay.get();
            assertEquals(1, s.eject().replayed());
        }
        assertEquals(recorded, replayed);
        assertFalse(recorded.isBlank());
        return recorded;
    }

    private static String stream(OpenAiStreamingChatModel model) {
        StringBuilder parts = new StringBuilder();
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        model.chat(PROMPT, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                synchronized (parts) {
                    parts.append(partial);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        try {
            ChatResponse r = done.get(60, TimeUnit.SECONDS);
            synchronized (parts) {
                return parts + " => " + r.aiMessage().text();
            }
        } catch (Exception e) {
            throw new AssertionError("streaming failed: " + e, e);
        }
    }

    private static byte[] speech(String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Cassette4j.baseUrl("openai") + "/v1/audio/speech"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"gpt-4o-mini-tts\",\"voice\":\"alloy\",\"input\":\"Hola\",\"response_format\":\"mp3\"}"))
                    .build();
            HttpResponse<byte[]> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new AssertionError("speech returned " + r.statusCode() + ": "
                        + new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return r.body();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
