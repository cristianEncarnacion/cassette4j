package io.github.cristianencarnacion.cassette4j;

import java.util.Locale;

/** How a cassette behaves when the code under test calls an LLM. */
public enum Mode {

    /** Use the global setting ({@code CASSETTE4J_MODE}), else REPLAY on CI, else AUTO. */
    DEFAULT,

    /** Replay recorded calls; record the ones that are missing. Good default for local development. */
    AUTO,

    /** Only replay. A call that is not in the cassette fails the test. Use it on CI. */
    REPLAY,

    /** Always call the real API and overwrite the cassette with what happened in this run. */
    RECORD,

    /** Call the real API and record nothing. */
    PASSTHROUGH;

    /** System property that overrides every cassette's mode. */
    public static final String MODE_PROPERTY = "cassette4j.mode";

    /** Environment variable that overrides every cassette's mode. */
    public static final String MODE_ENV = "CASSETTE4J_MODE";

    /**
     * Resolves the effective mode.
     *
     * <p>Order: global override (system property, then environment variable) &gt; the mode
     * declared on the cassette &gt; REPLAY when {@code CI=true} &gt; AUTO.</p>
     */
    public static Mode resolve(Mode declared) {
        String override = System.getProperty(MODE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(MODE_ENV);
        }
        if (override != null && !override.isBlank()) {
            Mode m = Mode.valueOf(override.trim().toUpperCase(Locale.ROOT));
            if (m != DEFAULT) {
                return m;
            }
        }
        if (declared != null && declared != DEFAULT) {
            return declared;
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return REPLAY;
        }
        return AUTO;
    }
}
