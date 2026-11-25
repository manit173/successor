package com.successor.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The course requires our own implementations: the algorithm package may import only {@code java.*}. */
class ArchitectureTest {
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    @Test
    void algorithmsImportOnlyTheJavaStandardLibrary() throws IOException {
        Path algo = Path.of("src/main/java/com/successor/engine/algo");
        assertTrue(Files.isDirectory(algo), "algorithm package missing: " + algo.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(algo)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = IMPORT.matcher(Files.readString(f));
                while (m.find()) {
                    String imported = m.group(2);
                    if (!imported.startsWith("java.") && !imported.startsWith("com.successor.engine.algo")) {
                        violations.add(algo.relativize(f) + " imports " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "non-java imports in algo/: " + violations);
    }
}
