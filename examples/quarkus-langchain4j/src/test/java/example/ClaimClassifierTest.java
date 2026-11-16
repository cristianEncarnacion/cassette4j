package example;

import io.github.cristianencarnacion.cassette4j.junit5.LlmCassette;
import io.github.cristianencarnacion.cassette4j.quarkus.Cassette4jTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the real AI service against a recorded OpenAI answer: no network, no API key, no cost.
 *
 * <p>The cassette compares only "model" and "messages", so default parameters that change between
 * quarkus-langchain4j versions (temperature, top_p, ...) do not break the test. Change the prompt
 * and the test fails, showing what changed.</p>
 */
@QuarkusTest
@QuarkusTestResource(Cassette4jTestResource.class)
class ClaimClassifierTest {

    @Inject
    ClaimClassifier classifier;

    @Test
    @LlmCassette(matchFields = {"model", "messages"})
    void classifiesACarAccident() {
        assertEquals("AUTO", classifier.classify("I crashed my car into a wall").trim());
    }
}
