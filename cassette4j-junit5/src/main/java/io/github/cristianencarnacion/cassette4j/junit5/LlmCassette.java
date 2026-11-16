package io.github.cristianencarnacion.cassette4j.junit5;

import io.github.cristianencarnacion.cassette4j.Mode;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records the LLM calls a test makes the first time it runs and replays them afterwards.
 *
 * <p>On a method it applies to that test; on a class it applies to every test in the class
 * (a method-level annotation overrides the class-level one). Each test gets its own cassette:
 * {@code src/test/resources/cassettes/<TestClass>/<testMethod>.json}.</p>
 *
 * <pre>{@code
 * @LlmCassette
 * @Test
 * void classifiesAClaim() {
 *     assertEquals("AUTO", classifier.classify("I crashed my car"));
 * }
 * }</pre>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(Cassette4jExtension.class)
public @interface LlmCassette {

    /** Cassette name (file name without {@code .json}). Defaults to the test method name. */
    String value() default "";

    /** Mode for this cassette. By default the global setting decides (REPLAY on CI, AUTO elsewhere). */
    Mode mode() default Mode.DEFAULT;

    /** JSON fields ignored when matching requests, at any depth, e.g. {@code {"user", "seed"}}. */
    String[] ignoreFields() default {};

    /**
     * When set, only these top-level JSON fields of the request are compared, e.g.
     * {@code {"model", "messages"}}. Useful when the client adds defaults (temperature, top_p, ...)
     * that change between library versions.
     */
    String[] matchFields() default {};

    /**
     * Real API for requests whose path does not start with a known alias such as
     * {@code /openai} or {@code /anthropic}. Example: {@code "https://llm.mycompany.com"}.
     */
    String upstream() default "";
}
