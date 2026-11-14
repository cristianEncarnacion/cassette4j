package io.github.cristianencarnacion.cassette4j.quarkus;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Cassette4jTestResourceTest {

    @Test
    void pointsLangChain4jProvidersAtTheProxyByDefault() {
        Cassette4jTestResource resource = new Cassette4jTestResource();
        resource.init(Map.of());
        Map<String, String> config = resource.start();
        assertEquals(Cassette4j.baseUrl() + "/openai/v1/", config.get("quarkus.langchain4j.openai.base-url"));
        assertEquals(Cassette4j.baseUrl() + "/anthropic/v1/", config.get("quarkus.langchain4j.anthropic.base-url"));
    }

    @Test
    void initArgsChooseTheProperties() {
        Cassette4jTestResource resource = new Cassette4jTestResource();
        resource.init(Map.of("my.llm.url", "/mistral/v1"));
        Map<String, String> config = resource.start();
        assertEquals(1, config.size());
        assertEquals(Cassette4j.baseUrl() + "/mistral/v1", config.get("my.llm.url"));
    }
}
