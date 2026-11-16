package com.transunion.xmlsplitter.core.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.transunion.xmlsplitter.core.PartSummary;
import com.transunion.xmlsplitter.core.SplitException;
import com.transunion.xmlsplitter.core.SplitSettings;

/**
 * Writes {@code manifest.json} (parts in order, with counts) and the empty {@code _SUCCESS} marker that
 * downstream consumers can wait for.
 */
public final class ManifestWriter {

    public static final String MANIFEST_FILE = "manifest.json";
    public static final String SUCCESS_FILE = "_SUCCESS";

    private ManifestWriter() {
    }

    public static void write(Path dir, Path input, String encoding, SplitSettings settings, List<PartSummary> parts) {
        long policies = parts.stream().mapToLong(PartSummary::policies).sum();
        long transactions = parts.stream().mapToLong(PartSummary::transactions).sum();
        String partsJson = parts.stream()
                .map(part -> "    {\"file\": " + quote(part.fileName())
                        + ", \"policies\": " + part.policies()
                        + ", \"transactions\": " + part.transactions()
                        + ", \"bytes\": " + part.bytes()
                        + ", \"overLimit\": " + part.overLimit() + "}")
                .collect(Collectors.joining(",\n"));
        String json = "{\n"
                + "  \"input\": " + quote(input.toAbsolutePath().toString()) + ",\n"
                + "  \"encoding\": " + quote(encoding) + ",\n"
                + "  \"maxTransactionsPerFile\": " + settings.maxTransactionsPerFile() + ",\n"
                + "  \"splitElement\": " + quote(settings.splitElement()) + ",\n"
                + "  \"countedElement\": " + quote(settings.countedElement()) + ",\n"
                + "  \"headerMode\": " + quote(settings.headerMode().toString()) + ",\n"
                + "  \"trailerMode\": " + quote(settings.trailerMode().toString()) + ",\n"
                + "  \"totalPolicies\": " + policies + ",\n"
                + "  \"totalTransactions\": " + transactions + ",\n"
                + "  \"parts\": [\n" + partsJson + "\n  ]\n"
                + "}\n";
        try {
            Files.writeString(dir.resolve(MANIFEST_FILE), json, StandardCharsets.UTF_8);
            Files.createFile(dir.resolve(SUCCESS_FILE));
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE, "Cannot write manifest: " + e.getMessage(), e);
        }
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
