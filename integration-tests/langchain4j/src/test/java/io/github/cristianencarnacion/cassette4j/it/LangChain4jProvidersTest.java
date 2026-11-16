package io.github.cristianencarnacion.cassette4j.it;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.cristianencarnacion.cassette4j.Cassette4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real LangChain4j clients for OpenAI, Anthropic, Gemini and Ollama, plain and streaming,
 * recorded and replayed through cassette4j. No internet, no API keys.
 */
class LangChain4jProvidersTest {

    private static final String REPLAY_KEY = "replay-only";

    private static FakeProviders fake;

    @TempDir
    Path dir;

    @BeforeAll
    static void start() throws Exception {
        fake = new FakeProviders();
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    // ------------------------------------------------------------------ OpenAI

    @Test
    void openAi() {
        Function<String, ChatModel> model = key -> OpenAiChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeopenai") + "/v1")
                .apiKey(key).modelName("gpt-4o-mini").maxRetries(0).build();
        assertEquals("Hola desde OpenAI", chat(dir.resolve("openai.json"), model, FakeProviders.OPENAI_KEY));
    }

    @Test
    void openAiStreaming() {
        Function<String, StreamingChatModel> model = key -> OpenAiStreamingChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeopenai") + "/v1")
                .apiKey(key).modelName("gpt-4o-mini").build();
        assertEquals("Ho|la desde OpenAI => Hola desde OpenAI",
                stream(dir.resolve("openai-stream.json"), model, FakeProviders.OPENAI_KEY));
    }

    // ------------------------------------------------------------------ Anthropic

    @Test
    void anthropic() {
        Function<String, ChatModel> model = key -> AnthropicChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeanthropic") + "/v1/")
                .apiKey(key).modelName("claude-haiku-4-5").maxTokens(100).maxRetries(0).build();
        assertEquals("Hola desde Anthropic", chat(dir.resolve("anthropic.json"), model, FakeProviders.ANTHROPIC_KEY));
    }

    @Test
    void anthropicStreaming() {
        Function<String, StreamingChatModel> model = key -> AnthropicStreamingChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeanthropic") + "/v1/")
                .apiKey(key).modelName("claude-haiku-4-5").maxTokens(100).build();
        assertEquals("Ho|la desde Anthropic => Hola desde Anthropic",
                stream(dir.resolve("anthropic-stream.json"), model, FakeProviders.ANTHROPIC_KEY));
    }

    // ------------------------------------------------------------------ Gemini

    @Test
    void gemini() {
        Function<String, ChatModel> model = key -> GoogleAiGeminiChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakegemini") + "/v1beta")
                .apiKey(key).modelName("gemini-2.5-flash").maxRetries(0).build();
        assertEquals("Hola desde Gemini", chat(dir.resolve("gemini.json"), model, FakeProviders.GEMINI_KEY));
    }

    @Test
    void geminiStreaming() {
        Function<String, StreamingChatModel> model = key -> GoogleAiGeminiStreamingChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakegemini") + "/v1beta")
                .apiKey(key).modelName("gemini-2.5-flash").build();
        assertEquals("Ho|la desde Gemini => Hola desde Gemini",
                stream(dir.resolve("gemini-stream.json"), model, FakeProviders.GEMINI_KEY));
    }

    // ------------------------------------------------------------------ Ollama

    @Test
    void ollama() {
        Function<String, ChatModel> model = key -> OllamaChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeollama") + "/")
                .modelName("llama3.2").maxRetries(0).build();
        assertEquals("Hola desde Ollama", chat(dir.resolve("ollama.json"), model, "unused"));
    }

    @Test
    void ollamaStreaming() {
        Function<String, StreamingChatModel> model = key -> OllamaStreamingChatModel.builder()
                .baseUrl(Cassette4j.baseUrl("fakeollama") + "/")
                .modelName("llama3.2").build();
        assertEquals("Ho|la desde Ollama => Hola desde Ollama",
                stream(dir.resolve("ollama-stream.json"), model, "unused"));
    }

    // ------------------------------------------------------------------ helpers

    private static String chat(Path cassette, Function<String, ChatModel> model, String realKey) {
        return RoundTrip.check(fake, cassette,
                () -> model.apply(realKey).chat("Saluda en una frase"),
                () -> model.apply(REPLAY_KEY).chat("Saluda en una frase"));
    }

    private static String stream(Path cassette, Function<String, StreamingChatModel> model, String realKey) {
        return RoundTrip.check(fake, cassette,
                () -> collect(model.apply(realKey)),
                () -> collect(model.apply(REPLAY_KEY)));
    }

    /** Returns the partial responses joined by "|", then " => " and the final text. */
    private static String collect(StreamingChatModel model) {
        StringBuilder parts = new StringBuilder();
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        model.chat("Saluda en una frase", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial != null && !partial.isEmpty()) {
                    synchronized (parts) {
                        parts.append(parts.length() == 0 ? "" : "|").append(partial);
                    }
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
            ChatResponse response = done.get(30, TimeUnit.SECONDS);
            synchronized (parts) {
                return parts + " => " + response.aiMessage().text();
            }
        } catch (Exception e) {
            throw new AssertionError("streaming call failed: " + e, e);
        }
    }
}
