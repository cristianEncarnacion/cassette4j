package io.github.cristianencarnacion.cassette4j;

import java.util.List;

/**
 * Settings for one inserted cassette.
 *
 * @param mode         declared mode ({@link Mode#DEFAULT} lets the global setting decide)
 * @param ignoreFields JSON fields ignored when matching requests, at any depth (e.g. {@code "user"}, {@code "seed"})
 * @param upstream     real API for requests that do not start with a known alias; may be empty
 * @param matchFields  when not empty, only these top-level JSON fields are compared
 *                     (e.g. {@code "model"}, {@code "messages"}); everything else in the body is ignored
 */
public record Options(Mode mode, List<String> ignoreFields, String upstream, List<String> matchFields) {

    public Options {
        mode = mode == null ? Mode.DEFAULT : mode;
        ignoreFields = ignoreFields == null ? List.of() : List.copyOf(ignoreFields);
        upstream = upstream == null ? "" : upstream;
        matchFields = matchFields == null ? List.of() : List.copyOf(matchFields);
    }

    public Options(Mode mode, List<String> ignoreFields, String upstream) {
        this(mode, ignoreFields, upstream, List.of());
    }

    public static Options defaults() {
        return new Options(Mode.DEFAULT, List.of(), "", List.of());
    }

    public Options withMode(Mode m) {
        return new Options(m, ignoreFields, upstream, matchFields);
    }

    public Options withIgnoreFields(String... fields) {
        return new Options(mode, List.of(fields), upstream, matchFields);
    }

    public Options withUpstream(String url) {
        return new Options(mode, ignoreFields, url, matchFields);
    }

    public Options withMatchFields(String... fields) {
        return new Options(mode, ignoreFields, upstream, List.of(fields));
    }
}
