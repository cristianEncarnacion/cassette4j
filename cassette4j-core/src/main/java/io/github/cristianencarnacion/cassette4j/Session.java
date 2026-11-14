package io.github.cristianencarnacion.cassette4j;

import java.nio.file.Path;

/** An inserted cassette. Closing it ejects the cassette and saves new recordings. */
public final class Session implements AutoCloseable {

    private final Path file;
    private final Mode mode;
    private Result result;

    Session(Path file, Mode mode) {
        this.file = file;
        this.mode = mode;
    }

    public Path file() {
        return file;
    }

    /** The effective mode after applying global overrides. */
    public Mode mode() {
        return mode;
    }

    /** Ejects the cassette (idempotent) and returns what happened. Never throws on misses. */
    public synchronized Result eject() {
        if (result == null) {
            result = Cassette4j.eject();
        }
        return result;
    }

    /**
     * Ejects the cassette and fails with an {@link AssertionError} if some LLM call could not be
     * answered from it.
     */
    @Override
    public void close() {
        Result r = eject();
        r.warnings().forEach(w -> System.err.println("cassette4j: " + w));
        if (r.hasMisses()) {
            throw new AssertionError(r.describeMisses());
        }
    }
}
