package io.github.cristianencarnacion.cassette4j.it;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real Spring AI OpenAI client, plain and streaming, recorded and replayed through cassette4j. */
class SpringAiOpenAiTest {

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

    private static OpenAiChatModel model(String key) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(Cassette4j.baseUrl("fakeopenai") + "/v1")
                        .apiKey(key)
                        .model("gpt-4o-mini")
                        .maxRetries(0)
                        .build())
                .build();
    }

    @Test
    void call() {
        String answer = RoundTrip.check(fake, dir.resolve("spring-ai.json"),
                () -> model(FakeProviders.OPENAI_KEY).call("Saluda en una frase"),
                () -> model("replay-only").call("Saluda en una frase"));
        assertEquals("Hola desde OpenAI", answer);
    }

    @Test
    void stream() {
        String answer = RoundTrip.check(fake, dir.resolve("spring-ai-stream.json"),
                () -> streamed(model(FakeProviders.OPENAI_KEY)),
                () -> streamed(model("replay-only")));
        assertEquals("Hola desde OpenAI", answer);
    }

    private static String streamed(OpenAiChatModel model) {
        List<String> parts = model.stream("Saluda en una frase").collectList().block(Duration.ofSeconds(30));
        return parts == null ? "" : String.join("", parts);
    }
}
