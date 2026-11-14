package io.github.cristianencarnacion.cassette4j.junit5;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** A call that is not in the cassette must fail the test with a helpful message. */
class MissingCallTest {

    @Test
    void unrecordedCallFailsTheTest() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MissingCallCase.class))
                .execute()
                .testEvents();

        assertEquals(1, tests.failed().count());
        Throwable failure = tests.failed().stream().findFirst().orElseThrow()
                .getPayload(org.junit.platform.engine.TestExecutionResult.class).orElseThrow()
                .getThrowable().orElseThrow();
        assertTrue(failure.getMessage().contains("closest recorded request differs"), failure.getMessage());
        assertTrue(failure.getMessage().contains("My house flooded"), failure.getMessage());
    }
}
