package com.transunion.xmlsplitter.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One split run.
 *
 * @param outputDir directory that will contain the parts; it is published atomically
 * @param schema    optional XSD every output part is validated against; {@code null} to skip
 */
public record SplitRequest(Path input, Path outputDir, SplitSettings settings, boolean overwrite, Path schema) {

    public SplitRequest {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(settings, "settings");
    }

    /** {@code <input folder>/<input name without extension>-parts}. */
    public static Path defaultOutputDir(Path input) {
        Path absolute = input.toAbsolutePath();
        return absolute.resolveSibling(baseName(absolute) + "-parts");
    }

    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
