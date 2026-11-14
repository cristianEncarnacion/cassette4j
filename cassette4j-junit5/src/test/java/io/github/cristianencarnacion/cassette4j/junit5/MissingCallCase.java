package io.github.cristianencarnacion.cassette4j.junit5;

import io.github.cristianencarnacion.cassette4j.Mode;
import org.junit.jupiter.api.Test;

/**
 * Not picked up by Surefire on its own (the name does not end in Test): {@link MissingCallTest}
 * runs it through the JUnit TestKit and expects it to fail.
 */
class MissingCallCase {

    @Test
    @LlmCassette(mode = Mode.REPLAY)
    void callsSomethingThatWasNeverRecorded() throws Exception {
        LlmCassetteTest.classify("My house flooded");
    }
}
