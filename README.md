# cassette4j

**Record LLM calls once. Replay them in your Java tests forever — offline, free and deterministic.**

[Leer en español](README.es.md)

Testing code that calls OpenAI, Anthropic, Gemini or any other LLM is painful:

- Calling the real API makes tests **slow, flaky and paid**, and they need internet and an API key on CI.
- Hand-written mocks **invent** the model's answer. Tool calls and streaming never look like the real thing, so tests pass and production breaks.

cassette4j sits between your code and the LLM API as a tiny local proxy. The first run records the real request and response into a JSON file next to your tests. Every run after that answers from the file: no network, no key, no cost, same answer every time.

```java
@Test
@LlmCassette
void classifiesACarAccident() {
    assertEquals("AUTO", classifier.classify("I crashed my car into a wall"));
}
```

Python has had this for years (vcrpy, pytest-recording). Java didn't have one that works with every LLM client and framework. Now it does.

## Features

- **Works with any client**: LangChain4j, Spring AI, Quarkus LangChain4j, the official OpenAI/Anthropic SDKs, plain `HttpClient`. If it lets you change the base URL, it works.
- **Streaming (SSE) and tool calls** are recorded and replayed byte for byte.
- **Secrets never reach the cassette**: request headers are not stored, and key-shaped strings (`sk-…`, `AIza…`, `?key=…`) are redacted.
- **Readable, diff-friendly cassettes**: JSON bodies are stored as JSON, streams as a list of events.
- **Smart matching**: key order does not matter, and volatile fields (`user`, `seed`, …) can be ignored.
- **Helpful failures**: when a prompt changes, the test fails and shows the closest recorded request and where it differs.
- **CI-safe by default**: with `CI=true`, cassettes only replay. A missing recording fails the build instead of calling the API.
- **Zero dependencies** in the core. Java 17+.
- **Quarkus and Spring Boot ready**, including class-loader isolation in `@QuarkusTest`.

## Install

```xml
<dependency>
    <groupId>io.github.cristianencarnacion</groupId>
    <artifactId>cassette4j-junit5</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

Quarkus users add `cassette4j-quarkus` instead (it includes the JUnit 5 module).

## Usage

The idea is always the same: point the base URL of your LLM client at `Cassette4j.baseUrl("<provider>")` and annotate the test with `@LlmCassette`.

### Quarkus + LangChain4j

```java
@QuarkusTest
@QuarkusTestResource(Cassette4jTestResource.class)   // or @WithTestResource on Quarkus 3.13+
class ClaimClassifierTest {

    @Inject
    ClaimClassifier classifier;   // a @RegisterAiService

    @Test
    @LlmCassette
    void classifiesACarAccident() {
        assertEquals("AUTO", classifier.classify("I crashed my car into a wall"));
    }
}
```

`src/test/resources/application.properties`:

```properties
# a placeholder key lets replays run without a real key
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:replay-only}
```

`Cassette4jTestResource` points `quarkus.langchain4j.openai.base-url` and `quarkus.langchain4j.anthropic.base-url` at the proxy. For other properties, pass init args: `@ResourceArg(name = "quarkus.langchain4j.mistralai.base-url", value = "mistral/v1/")`.

### Spring Boot + Spring AI

```java
@SpringBootTest
class SupportBotTest {

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> Cassette4j.baseUrl("openai"));
        registry.add("spring.ai.openai.api-key",
                () -> System.getenv().getOrDefault("OPENAI_API_KEY", "replay-only"));
    }

    @Autowired
    SupportBot bot;

    @Test
    @LlmCassette
    void answersRefundQuestions() {
        assertTrue(bot.answer("How do I get a refund?").contains("30 days"));
    }
}
```

### Plain LangChain4j

```java
OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl(Cassette4j.baseUrl("openai") + "/v1")
        .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", "replay-only"))
        .modelName("gpt-4o-mini")
        .build();
```

### Without JUnit

```java
try (Session s = Cassette4j.insert(Path.of("cassettes/greeting.json"))) {
    String answer = model.chat("Say hi");
}   // closing the session saves new recordings and fails if a call was missing
```

## Providers

| Base URL | Real API |
|---|---|
| `Cassette4j.baseUrl("openai")` | `https://api.openai.com` |
| `Cassette4j.baseUrl("anthropic")` | `https://api.anthropic.com` |
| `Cassette4j.baseUrl("gemini")` | `https://generativelanguage.googleapis.com` |
| `Cassette4j.baseUrl("mistral")` | `https://api.mistral.ai` |
| `Cassette4j.baseUrl("groq")` | `https://api.groq.com` |
| `Cassette4j.baseUrl("deepseek")` | `https://api.deepseek.com` |
| `Cassette4j.baseUrl("xai")` | `https://api.x.ai` |
| `Cassette4j.baseUrl("openrouter")` | `https://openrouter.ai` |
| `Cassette4j.baseUrl("ollama")` | `http://localhost:11434` |

Add your own (Azure OpenAI, a company gateway, …) with `-Dcassette4j.upstream.<alias>=https://…`, or use `@LlmCassette(upstream = "https://…")`.

## Modes

| Mode | What it does | When |
|---|---|---|
| `AUTO` | Replays what is recorded, records what is missing | Local development (default) |
| `REPLAY` | Only replays; a missing call fails the test | CI (default when `CI=true`) |
| `RECORD` | Calls the real API and overwrites the cassette | After changing prompts on purpose |
| `PASSTHROUGH` | Calls the real API, records nothing | Debugging against the live model |

Set it per test (`@LlmCassette(mode = Mode.REPLAY)`) or globally:

```bash
OPENAI_API_KEY=sk-... mvn test -Dcassette4j.mode=RECORD   # re-record everything
```

## Cassettes

Each test gets `src/test/resources/cassettes/<TestClass>/<testMethod>.json`. Commit them. A recorded interaction looks like this:

```json
{
  "request": {
    "method": "POST",
    "path": "/openai/v1/chat/completions",
    "json": { "model": "gpt-4o-mini", "messages": [ { "role": "user", "content": "..." } ] }
  },
  "response": {
    "status": 200,
    "contentType": "application/json",
    "json": { "choices": [ { "message": { "role": "assistant", "content": "AUTO" } } ] }
  }
}
```

Identical requests are answered in the order they were recorded, so agents that call the model several times with the same input work.

## Configuration

| System property | Default | Purpose |
|---|---|---|
| `cassette4j.mode` / env `CASSETTE4J_MODE` | — | Overrides the mode of every cassette |
| `cassette4j.dir` | `src/test/resources/cassettes` | Where cassettes live |
| `cassette4j.port` | random free port | Fixed proxy port |
| `cassette4j.upstream.<alias>` | — | Adds or overrides a provider alias |
| `cassette4j.upstream` | — | Real API for paths without an alias |
| `cassette4j.redact` | — | Extra comma-separated regexes to redact |

What is never recorded: request headers, responses with status 401/403/407/429 or 5xx (they are passed through and reported as warnings).

## How it works

`Cassette4j.baseUrl()` starts an HTTP server on `127.0.0.1` (once per JVM). `@LlmCassette` tells it which cassette to use through a small control endpoint. Because control happens over HTTP, it works even when Quarkus or Spring load your tests in a different class loader.

Requests are matched on method, path and body. JSON bodies are compared after sorting keys and removing ignored fields.

## Limitations (0.1.0)

- Tests that call the LLM must not run in parallel inside the same JVM (one cassette is active at a time).
- Streams are replayed in one go, not chunk by chunk with the original timing.
- WebSocket / realtime APIs are not supported.

## Related

- [EasyVCR](https://github.com/EasyPost/easyvcr-java) records generic HTTP for specific Java HTTP clients. cassette4j is LLM-aware (JSON matching, SSE, key redaction) and works with any client through its base URL.

## License

Apache 2.0
