package io.github.cristianencarnacion.cassette4j;

import java.util.List;

/**
 * What happened while a cassette was inserted.
 *
 * @param file     cassette file
 * @param mode     effective mode
 * @param replayed calls answered from the cassette
 * @param recorded calls sent to the real API and recorded
 * @param misses   calls that could not be answered (they make the test fail)
 * @param warnings things worth knowing that do not fail the test
 */
public record Result(String file,
                     Mode mode,
                     int replayed,
                     int recorded,
                     List<String> misses,
                     List<String> warnings) {

    public boolean hasMisses() {
        return !misses.isEmpty();
    }

    /** Human readable failure message for the misses. */
    public String describeMisses() {
        StringBuilder sb = new StringBuilder();
        sb.append("cassette4j: ").append(misses.size())
                .append(misses.size() == 1 ? " LLM call was" : " LLM calls were")
                .append(" not found in cassette ").append(file)
                .append(" (mode ").append(mode).append(")\n");
        for (int i = 0; i < misses.size(); i++) {
            sb.append("\n[").append(i + 1).append("] ").append(misses.get(i)).append('\n');
        }
        sb.append("\nIf the prompt changed on purpose, re-record with -Dcassette4j.mode=RECORD ")
                .append("(or CASSETTE4J_MODE=RECORD) and a real API key.");
        return sb.toString();
    }
}
