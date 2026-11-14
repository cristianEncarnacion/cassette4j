package io.github.cristianencarnacion.cassette4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRecordReplayTest {

    private FakeLlm llm;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        llm = new FakeLlm();
        dir = Files.createTempDirectory("cassette4j-test");
    }

    @AfterEach
    void tearDown() {
        llm.close();
    }

    @Test
    void autoRecordsOnceThenReplaysWithoutCallingTheApi() throws Exception {
        Path file = dir.resolve("greeting.json");

        String first;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            HttpResponse<String> r = FakeLlm.chat(FakeLlm.prompt("Say hi"));
            assertEquals(200, r.statusCode());
            first = r.body();
            Result result = s.eject();
            assertEquals(1, result.recorded());
        }
        assertTrue(Files.exists(file), "cassette should be written");
        assertEquals(1, llm.hits.get());

        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            HttpResponse<String> r = FakeLlm.chat(FakeLlm.prompt("Say hi"));
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"content\":\"answer 1\""), r.body());
            assertEquals(1, s.eject().replayed());
        }
        assertEquals(1, llm.hits.get(), "replay must not call the real API");
        assertTrue(first.contains("answer 1"));
    }

    @Test
    void replayWorksWithoutApiKey() throws Exception {
        Path file = dir.resolve("nokey.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            FakeLlm.chat(FakeLlm.prompt("ping"));
        }
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            HttpResponse<String> r = FakeLlm.post("/fake/v1/chat/completions", FakeLlm.prompt("ping"), false);
            assertEquals(200, r.statusCode());
        }
    }

    @Test
    void replayFailsClearlyWhenTheCallIsMissing() throws Exception {
        Path file = dir.resolve("missing.json");
        Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY));
        HttpResponse<String> r = FakeLlm.chat(FakeLlm.prompt("never recorded"));
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("cassette4j_miss"), r.body());
        AssertionError e = assertThrows(AssertionError.class, s::close);
        assertTrue(e.getMessage().contains("not found in cassette"), e.getMessage());
        assertTrue(e.getMessage().contains("empty or does not exist"), e.getMessage());
        assertEquals(0, llm.hits.get());
        assertFalse(Files.exists(file));
    }

    @Test
    void changedPromptPointsAtTheClosestRecordedRequest() throws Exception {
        Path file = dir.resolve("changed.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            FakeLlm.chat(FakeLlm.prompt("Classify this claim: car accident"));
        }
        Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY));
        FakeLlm.chat(FakeLlm.prompt("Classify this claim: house fire"));
        AssertionError e = assertThrows(AssertionError.class, s::close);
        assertTrue(e.getMessage().contains("closest recorded request differs"), e.getMessage());
        assertTrue(e.getMessage().contains("car accident"), e.getMessage());
        assertTrue(e.getMessage().contains("house fire"), e.getMessage());
    }

    @Test
    void streamingResponsesAreRecordedAndReplayedExactly() throws Exception {
        Path file = dir.resolve("stream.json");
        String req = "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hola\"}]}";
        String recorded;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            HttpResponse<String> r = FakeLlm.chat(req);
            recorded = r.body();
            assertEquals("text/event-stream", r.headers().firstValue("Content-Type").orElse(""));
        }
        String cassette = Files.readString(file);
        assertTrue(cassette.contains("\"events\""), cassette);
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            HttpResponse<String> r = FakeLlm.chat(req);
            assertEquals(recorded, r.body());
            assertEquals("text/event-stream", r.headers().firstValue("Content-Type").orElse(""));
        }
        assertEquals(1, llm.hits.get());
    }

    @Test
    void identicalCallsAreReplayedInRecordedOrder() throws Exception {
        Path file = dir.resolve("order.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            assertTrue(FakeLlm.chat(FakeLlm.prompt("again")).body().contains("answer 1"));
            assertTrue(FakeLlm.chat(FakeLlm.prompt("again")).body().contains("answer 2"));
        }
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            assertTrue(FakeLlm.chat(FakeLlm.prompt("again")).body().contains("answer 1"));
            assertTrue(FakeLlm.chat(FakeLlm.prompt("again")).body().contains("answer 2"));
            // more calls than recorded: the last answer is repeated
            assertTrue(FakeLlm.chat(FakeLlm.prompt("again")).body().contains("answer 2"));
        }
        assertEquals(2, llm.hits.get());
    }

    @Test
    void ignoredFieldsDoNotBreakMatching() throws Exception {
        Path file = dir.resolve("ignore.json");
        String a = "{\"model\":\"m\",\"user\":\"session-111\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}";
        String b = "{\"messages\":[{\"content\":\"x\",\"role\":\"user\"}],\"user\":\"session-222\",\"model\":\"m\"}";
        try (Session s = Cassette4j.insert(file, new Options(Mode.AUTO, java.util.List.of("user"), ""))) {
            FakeLlm.chat(a);
        }
        try (Session s = Cassette4j.insert(file, new Options(Mode.REPLAY, java.util.List.of("user"), ""))) {
            assertEquals(200, FakeLlm.chat(b).statusCode(), "key order and ignored fields must not matter");
        }
    }

    @Test
    void secretsNeverReachTheCassette() throws Exception {
        Path file = dir.resolve("secrets.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            FakeLlm.post("/fake/v1/chat/completions?key=AIzaSyA1234567890abcdefghijklmnopqrstu",
                    FakeLlm.prompt("hi"), true);
        }
        String cassette = Files.readString(file);
        assertFalse(cassette.contains(FakeLlm.API_KEY), "API key leaked into the cassette");
        assertFalse(cassette.contains("AIzaSy"), "query key leaked into the cassette");
        assertFalse(cassette.toLowerCase().contains("authorization"), "headers must not be stored");
        assertTrue(cassette.contains(Scrubber.REDACTED));

        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            HttpResponse<String> r = FakeLlm.post(
                    "/fake/v1/chat/completions?key=AIzaSyA1234567890abcdefghijklmnopqrstu", FakeLlm.prompt("hi"), true);
            assertEquals(200, r.statusCode(), "replay must match after scrubbing the query key");
        }
    }

    @Test
    void numbersAreReplayedByteForByte() throws Exception {
        Path file = dir.resolve("numbers.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            FakeLlm.chat(FakeLlm.prompt("n"));
        }
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.REPLAY))) {
            assertTrue(FakeLlm.chat(FakeLlm.prompt("n")).body().contains("\"temperature_used\":0.70"));
        }
    }

    @Test
    void authErrorsArePassedThroughButNotRecorded() throws Exception {
        Path file = dir.resolve("auth.json");
        Result result;
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            HttpResponse<String> r = FakeLlm.post("/fake/v1/chat/completions", FakeLlm.prompt("hi"), false);
            assertEquals(401, r.statusCode());
            result = s.eject();
        }
        assertEquals(0, result.recorded());
        assertTrue(result.warnings().get(0).contains("check your API key"), result.warnings().toString());
        assertFalse(Files.exists(file));
    }

    @Test
    void recordModeOverwritesTheCassette() throws Exception {
        Path file = dir.resolve("rerecord.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO))) {
            FakeLlm.chat(FakeLlm.prompt("one"));
            FakeLlm.chat(FakeLlm.prompt("two"));
        }
        assertEquals(2, Cassette.load(file).interactions().size());
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.RECORD))) {
            FakeLlm.chat(FakeLlm.prompt("one"));
        }
        assertEquals(1, Cassette.load(file).interactions().size());
        assertEquals(3, llm.hits.get());
    }

    @Test
    void passthroughCallsTheApiAndRecordsNothing() throws Exception {
        Path file = dir.resolve("passthrough.json");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.PASSTHROUGH))) {
            assertEquals(200, FakeLlm.chat(FakeLlm.prompt("live")).statusCode());
        }
        assertFalse(Files.exists(file));
        assertEquals(1, llm.hits.get());
    }

    @Test
    void callWithoutInsertedCassetteGetsAHelpfulError() throws Exception {
        HttpResponse<String> r = FakeLlm.chat(FakeLlm.prompt("orphan"));
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("no cassette is inserted"), r.body());
    }

    @Test
    void unknownUpstreamIsReported() throws Exception {
        Path file = dir.resolve("unknown.json");
        Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO));
        HttpResponse<String> r = FakeLlm.post("/not-an-alias/v1/chat", FakeLlm.prompt("x"), true);
        assertEquals(404, r.statusCode());
        AssertionError e = assertThrows(AssertionError.class, s::close);
        assertTrue(e.getMessage().contains("No real API is known"), e.getMessage());
    }

    @Test
    void cassetteUpstreamHandlesPathsWithoutAlias() throws Exception {
        Path file = dir.resolve("upstream.json");
        String fakeUrl = System.getProperty(Upstreams.ALIAS_PROPERTY_PREFIX + "fake");
        try (Session s = Cassette4j.insert(file, Options.defaults().withMode(Mode.AUTO).withUpstream(fakeUrl))) {
            assertEquals(200, FakeLlm.post("/v1/chat/completions", FakeLlm.prompt("plain"), true).statusCode());
        }
        assertEquals(1, llm.hits.get());
    }
}
