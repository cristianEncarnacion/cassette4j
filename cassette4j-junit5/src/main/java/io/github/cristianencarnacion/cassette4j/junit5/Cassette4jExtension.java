package io.github.cristianencarnacion.cassette4j.junit5;

import io.github.cristianencarnacion.cassette4j.Cassette4j;
import io.github.cristianencarnacion.cassette4j.Mode;
import io.github.cristianencarnacion.cassette4j.Options;
import io.github.cristianencarnacion.cassette4j.Result;
import io.github.cristianencarnacion.cassette4j.Session;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * JUnit 5 extension behind {@link LlmCassette}: inserts the cassette before each test and ejects
 * it afterwards. A test whose LLM calls are not in the cassette (in REPLAY mode) fails with a
 * message that shows the closest recorded request.
 *
 * <p>Works with {@code @QuarkusTest} and {@code @SpringBootTest}: the cassette is controlled
 * over the proxy's local HTTP channel, so class loaders do not matter.</p>
 */
public class Cassette4jExtension implements BeforeEachCallback, AfterEachCallback {

    /** One session per test thread. */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        Method method = context.getRequiredTestMethod();
        Class<?> testClass = context.getRequiredTestClass();
        LlmCassette onMethod = method.getAnnotation(LlmCassette.class);
        LlmCassette onClass = findOnClass(testClass);
        if (onMethod == null && onClass == null) {
            return;
        }

        String name = firstNonBlank(onMethod == null ? "" : onMethod.value(), method.getName());
        Mode mode = onMethod != null && onMethod.mode() != Mode.DEFAULT
                ? onMethod.mode()
                : onClass != null ? onClass.mode() : Mode.DEFAULT;
        String upstream = firstNonBlank(onMethod == null ? "" : onMethod.upstream(),
                onClass == null ? "" : onClass.upstream());
        List<String> ignore = onMethod != null && onMethod.ignoreFields().length > 0
                ? List.of(onMethod.ignoreFields())
                : onClass != null ? List.of(onClass.ignoreFields()) : List.of();

        Path file = Cassette4j.cassetteDir()
                .resolve(outerMostName(testClass))
                .resolve(sanitize(name) + ".json");
        CURRENT.set(Cassette4j.insert(file, new Options(mode, ignore, upstream)));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Session session = CURRENT.get();
        CURRENT.remove();
        if (session == null) {
            return;
        }
        Result result = session.eject();
        result.warnings().forEach(w -> System.err.println("cassette4j: " + w));
        if (result.recorded() > 0) {
            System.err.println("cassette4j: recorded " + result.recorded() + " call(s) into " + result.file());
        }
        if (result.hasMisses()) {
            if (context.getExecutionException().isPresent()) {
                // the test already failed; add context without hiding the original failure
                System.err.println(result.describeMisses());
            } else {
                throw new AssertionError(result.describeMisses());
            }
        }
    }

    private static LlmCassette findOnClass(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            LlmCassette a = c.getAnnotation(LlmCassette.class);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    /** Top-level class name, so @Nested tests share their parent's folder. */
    private static String outerMostName(Class<?> type) {
        Class<?> c = type;
        StringBuilder suffix = new StringBuilder();
        while (c.getEnclosingClass() != null) {
            suffix.insert(0, "/" + c.getSimpleName());
            c = c.getEnclosingClass();
        }
        return c.getSimpleName() + suffix;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._\\-]", "_");
    }
}
