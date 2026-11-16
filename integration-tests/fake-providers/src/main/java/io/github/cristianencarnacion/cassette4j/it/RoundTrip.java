package io.github.cristianencarnacion.cassette4j.it;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import io.github.cristianencarnacion.cassette4j.Mode;
import io.github.cristianencarnacion.cassette4j.Options;
import io.github.cristianencarnacion.cassette4j.Result;
import io.github.cristianencarnacion.cassette4j.Session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Records one call made by a real client library, then replays it with the fake API unused,
 * and checks that the client got exactly the same answer both times.
 */
public final class RoundTrip {

    private RoundTrip() {
    }

    /**
     * @param recordCall the call made with the real API key (reaches the fake API once)
     * @param replayCall the same call built with a dummy API key (must be answered by the cassette)
     * @return the answer the client saw
     */
    public static String check(FakeProviders fake, Path cassette, Supplier<String> recordCall, Supplier<String> replayCall) {
        int before = fake.hits();
        String recorded;
        try (Session s = Cassette4j.insert(cassette, Options.defaults().withMode(Mode.RECORD))) {
            recorded = recordCall.get();
            Result r = s.eject();
            if (r.recorded() < 1) {
                throw new AssertionError("nothing was recorded for " + cassette);
            }
        }
        if (fake.hits() != before + 1) {
            throw new AssertionError("expected exactly one call to the API while recording, got " + (fake.hits() - before));
        }

        String text = read(cassette);
        for (String secret : new String[] {FakeProviders.OPENAI_KEY, FakeProviders.ANTHROPIC_KEY, FakeProviders.GEMINI_KEY}) {
            if (text.contains(secret)) {
                throw new AssertionError("API key leaked into " + cassette);
            }
        }

        String replayed;
        try (Session s = Cassette4j.insert(cassette, Options.defaults().withMode(Mode.REPLAY))) {
            replayed = replayCall.get();
            Result r = s.eject();
            if (r.replayed() < 1) {
                throw new AssertionError("nothing was replayed from " + cassette);
            }
        }
        if (fake.hits() != before + 1) {
            throw new AssertionError("replay must not call the API");
        }
        if (!recorded.equals(replayed)) {
            throw new AssertionError("replayed answer differs:\n  recorded: " + recorded + "\n  replayed: " + replayed);
        }
        return replayed;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
