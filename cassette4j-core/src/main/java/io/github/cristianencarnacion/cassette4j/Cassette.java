package io.github.cristianencarnacion.cassette4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cassette file: an ordered list of recorded interactions. */
public final class Cassette {

    static final int FORMAT_VERSION = 1;

    private final List<Interaction> interactions;

    public Cassette(List<Interaction> interactions) {
        this.interactions = new ArrayList<>(interactions);
    }

    public List<Interaction> interactions() {
        return List.copyOf(interactions);
    }

    /** Loads a cassette, or returns an empty one when the file does not exist. */
    @SuppressWarnings("unchecked")
    public static Cassette load(Path file) {
        if (!Files.exists(file)) {
            return new Cassette(List.of());
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Cassette root must be an object: " + file);
            }
            Object list = ((Map<String, Object>) map).get("interactions");
            List<Interaction> out = new ArrayList<>();
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    out.add(Interaction.fromMap((Map<String, Object>) o));
                }
            }
            return new Cassette(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read cassette " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cassette " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the cassette atomically (pretty JSON, stable ordering, trailing newline). */
    public void save(Path file) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cassette4j", new Json.Num(Integer.toString(FORMAT_VERSION)));
        List<Object> list = new ArrayList<>();
        for (Interaction i : interactions) {
            list.add(i.toMap());
        }
        root.put("interactions", list);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, ".cassette4j", ".tmp");
            Files.writeString(tmp, Json.pretty(root) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cassette " + file, e);
        }
    }
}
