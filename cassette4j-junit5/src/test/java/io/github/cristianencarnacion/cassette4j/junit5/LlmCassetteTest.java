package io.github.cristianencarnacion.cassette4j.junit5;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import io.github.cristianencarnacion.cassette4j.Mode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays a cassette committed under src/test/resources/cassettes. No network, no API key.
 */
class LlmCassetteTest {

    static final HttpClient CLIENT = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    static HttpResponse<String> classify(String claim) throws Exception {
        String body = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":"
                + "\"Classify this insurance claim in one word: " + claim + "\"}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(Cassette4j.baseUrl("openai") + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer replay-only")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @LlmCassette(mode = Mode.REPLAY)
    void answersFromTheCommittedCassette() throws Exception {
        HttpResponse<String> r = classify("I crashed my car into a wall");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"content\":\"AUTO\""), r.body());
    }
}
