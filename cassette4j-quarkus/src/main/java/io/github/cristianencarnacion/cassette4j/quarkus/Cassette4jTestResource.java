package io.github.cristianencarnacion.cassette4j.quarkus;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the cassette4j proxy before the Quarkus application and points the LLM base URLs at it.
 *
 * <pre>{@code
 * @QuarkusTest
 * @QuarkusTestResource(Cassette4jTestResource.class)
 * class ClaimClassifierTest {
 *
 *     @Inject ClaimClassifier classifier;
 *
 *     @Test
 *     @LlmCassette
 *     void classifiesACarAccident() {
 *         assertEquals("AUTO", classifier.classify("I crashed my car into a wall"));
 *     }
 * }
 * }</pre>
 *
 * <p>Without init args it sets {@code quarkus.langchain4j.openai.base-url} and
 * {@code quarkus.langchain4j.anthropic.base-url}. To point any other property at the proxy, pass
 * init args: the name is the config property and the value is the path on the proxy, e.g.
 * {@code @ResourceArg(name = "quarkus.langchain4j.mistralai.base-url", value = "mistral/v1/")}.</p>
 *
 * <p>The API key still has to be configured. Use a placeholder so replays work without a real key:
 * {@code quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:replay-only}}.</p>
 */
public class Cassette4jTestResource implements QuarkusTestResourceLifecycleManager {

    private Map<String, String> initArgs = Map.of();

    @Override
    public void init(Map<String, String> initArgs) {
        this.initArgs = initArgs == null ? Map.of() : initArgs;
    }

    @Override
    public Map<String, String> start() {
        String base = Cassette4j.baseUrl();
        Map<String, String> mapping = new LinkedHashMap<>();
        if (initArgs.isEmpty()) {
            mapping.put("quarkus.langchain4j.openai.base-url", "openai/v1/");
            mapping.put("quarkus.langchain4j.anthropic.base-url", "anthropic/v1/");
        } else {
            mapping.putAll(initArgs);
        }
        Map<String, String> config = new LinkedHashMap<>();
        mapping.forEach((property, path) -> config.put(property, join(base, path)));
        return config;
    }

    @Override
    public void stop() {
        // The proxy is shared by every test in the JVM and stops with it.
    }

    static String join(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
