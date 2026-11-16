package io.github.cristianencarnacion.cassette4j;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plain program that uses cassette4j must still end when its main method returns. */
class JvmExitTest {

    public static void main(String[] args) throws Exception {
        try (FakeLlm llm = new FakeLlm();
             Session s = Cassette4j.insert(Path.of(args[0]), Options.defaults().withMode(Mode.RECORD))) {
            if (FakeLlm.chat(FakeLlm.prompt("bye")).statusCode() != 200) {
                System.exit(3);
            }
        }
    }

    @Test
    void theProxyDoesNotKeepTheJvmAlive() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path cassette = Files.createTempDirectory("cassette4j-exit").resolve("exit.json");
        Process p = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                JvmExitTest.class.getName(), cassette.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
        }
        assertTrue(exited, "the JVM did not exit after main returned");
        assertEquals(0, p.exitValue());
        assertTrue(Files.exists(cassette), "cassette should be saved");
    }
}
