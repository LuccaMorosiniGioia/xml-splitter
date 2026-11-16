package com.transunion.xmlsplitter.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Defaults from {@code application.yml} (prefix {@code splitter}); command-line options override them.
 * Modes are kept as text and parsed by the core, so the accepted spelling is the same in both places.
 */
@ConfigurationProperties(prefix = "splitter")
public record SplitterProperties(
        @DefaultValue("5000") int maxTransactions,
        @DefaultValue("Policy") String splitElement,
        @DefaultValue("Transaction") String countedElement,
        @DefaultValue("every-file") String headerMode,
        @DefaultValue("last-file") String trailerMode,
        String outputDir,
        @DefaultValue("false") boolean overwrite) {
}
