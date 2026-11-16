package com.transunion.xmlsplitter.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record SplitResult(
        Path outputDir,
        String encoding,
        List<PartSummary> parts,
        long totalPolicies,
        long totalTransactions,
        Duration elapsed) {

    public SplitResult {
        parts = List.copyOf(parts);
    }
}
