package io.github.cristianencarnacion.cassette4j;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Quarkus and Spring Boot devtools load test code in their own class loaders. Every copy of
 * cassette4j in the JVM must end up talking to the same proxy.
 */
class ClassLoaderIsolationTest {

    @Test
    void separateClassLoadersShareOneProxy() throws Exception {
        int here = Cassette4j.port();
        URL coreClasses = Cassette4j.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{coreClasses}, ClassLoader.getPlatformClassLoader())) {
            Class<?> other = isolated.loadClass(Cassette4j.class.getName());
            assertFalse(other == Cassette4j.class, "the class must really be loaded twice");
            int there = (int) other.getMethod("port").invoke(null);
            assertEquals(here, there);
        }
    }
}
