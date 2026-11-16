package com.transunion.xmlsplitter.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;

import com.transunion.xmlsplitter.core.HeaderMode;
import com.transunion.xmlsplitter.core.SplitException;
import com.transunion.xmlsplitter.core.SplitRequest;
import com.transunion.xmlsplitter.core.SplitSettings;
import com.transunion.xmlsplitter.core.TrailerMode;

/**
 * Turns command-line arguments plus {@link SplitterProperties} defaults into a {@link SplitRequest}.
 * Command-line values always win over {@code application.yml}.
 */
public class CommandLineParser {

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String MAX_TRANSACTIONS = "max-transactions";
    static final String HEADER_MODE = "header-mode";
    static final String TRAILER_MODE = "trailer-mode";
    static final String SPLIT_ELEMENT = "split-element";
    static final String COUNTED_ELEMENT = "counted-element";
    static final String SCHEMA = "schema";
    static final String OVERWRITE = "overwrite";
    static final String HELP = "help";

    private static final Set<String> KNOWN = Set.of(INPUT, OUTPUT, MAX_TRANSACTIONS, HEADER_MODE, TRAILER_MODE,
            SPLIT_ELEMENT, COUNTED_ELEMENT, SCHEMA, OVERWRITE, HELP);

    private final SplitterProperties defaults;

    public CommandLineParser(SplitterProperties defaults) {
        this.defaults = defaults;
    }

    public boolean isHelp(ApplicationArguments args) {
        return args.containsOption(HELP) || (args.getOptionNames().isEmpty() && args.getNonOptionArgs().isEmpty());
    }

    public SplitRequest parse(ApplicationArguments args) {
        rejectUnknownOptions(args);
        Path input = Path.of(inputArgument(args));
        String outputDir = single(args, OUTPUT, defaults.outputDir());
        Path output = isBlank(outputDir) ? SplitRequest.defaultOutputDir(input) : Path.of(outputDir);

        SplitSettings settings = new SplitSettings(
                parseInt(single(args, MAX_TRANSACTIONS, String.valueOf(defaults.maxTransactions())), MAX_TRANSACTIONS),
                single(args, SPLIT_ELEMENT, defaults.splitElement()),
                single(args, COUNTED_ELEMENT, defaults.countedElement()),
                HeaderMode.parse(single(args, HEADER_MODE, defaults.headerMode())),
                TrailerMode.parse(single(args, TRAILER_MODE, defaults.trailerMode())));

        String schema = single(args, SCHEMA, null);
        boolean overwrite = flag(args, OVERWRITE, defaults.overwrite());
        return new SplitRequest(input, output, settings, overwrite, isBlank(schema) ? null : Path.of(schema));
    }

    public static String usage() {
        return """
                Usage: xml-splitter --input=<file.xml> [options]
                       xml-splitter <file.xml> [options]

                Splits a large XML into files holding whole <Policy> elements, each with at most
                X <Transaction> elements (a single Policy larger than X goes alone in its own file).

                Options (defaults come from application.yml):
                  --input=<file>              XML file to split
                  --output=<folder>           output folder (default: <input folder>/<input name>-parts)
                  --max-transactions=<X>      max Transactions per file (default 5000)
                  --header-mode=<mode>        every-file | first-only (default every-file)
                  --trailer-mode=<mode>       last-file | every-file (default last-file)
                  --split-element=<name>      element never split (default Policy)
                  --counted-element=<name>    element counted against X (default Transaction)
                  --schema=<file.xsd>         also validate every output part against this XSD
                  --overwrite                 replace an output folder from a previous run
                  --help                      show this help

                Exit codes:
                """ + exitCodes();
    }

    private static String exitCodes() {
        StringBuilder text = new StringBuilder();
        for (ExitCode code : ExitCode.values()) {
            text.append(String.format("  %d  %s%n", code.code(), code.description()));
        }
        return text.toString();
    }

    private static void rejectUnknownOptions(ApplicationArguments args) {
        for (String name : args.getOptionNames()) {
            // spring.* and splitter.* are regular Spring Boot property overrides
            if (!KNOWN.contains(name) && !name.startsWith("spring.") && !name.startsWith("splitter.")
                    && !name.startsWith("logging.")) {
                throw invalid("Unknown option --" + name + " (see --help)");
            }
        }
    }

    private static String inputArgument(ApplicationArguments args) {
        String option = single(args, INPUT, null);
        List<String> positional = args.getNonOptionArgs();
        if (positional.size() > 1 || (option != null && !positional.isEmpty())) {
            throw invalid("Exactly one input file is expected");
        }
        String input = option != null ? option : positional.isEmpty() ? null : positional.get(0);
        if (isBlank(input)) {
            throw invalid("Missing input file (--input=<file.xml>)");
        }
        return input;
    }

    private static String single(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return fallback;
        }
        if (values.size() != 1 || isBlank(values.get(0))) {
            throw invalid("Option --" + name + " needs exactly one value");
        }
        return values.get(0).trim();
    }

    private static boolean flag(ApplicationArguments args, String name, boolean fallback) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return fallback;
        }
        if (values.isEmpty()) {
            return true;
        }
        String value = values.get(values.size() - 1).trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        throw invalid("Option --" + name + " takes no value (or true/false)");
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid("Option --" + name + " must be a whole number, got '" + value + "'");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SplitException invalid(String message) {
        return new SplitException(SplitException.Reason.INVALID_ARGUMENTS, message);
    }
}
