package com.transunion.xmlsplitter.cli;

import java.io.PrintStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import com.transunion.xmlsplitter.core.PartSummary;
import com.transunion.xmlsplitter.core.SplitException;
import com.transunion.xmlsplitter.core.SplitRequest;
import com.transunion.xmlsplitter.core.SplitResult;
import com.transunion.xmlsplitter.core.XmlSplitService;

/**
 * Thin command-line adapter: parse arguments, call the core service, print a summary, set the exit code.
 */
@Component
public class SplitCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SplitCommand.class);

    private final XmlSplitService service;
    private final CommandLineParser parser;
    private final PrintStream out;
    private ExitCode exitCode = ExitCode.SUCCESS;

    @Autowired
    public SplitCommand(XmlSplitService service, SplitterProperties properties) {
        this(service, new CommandLineParser(properties), System.out);
    }

    SplitCommand(XmlSplitService service, CommandLineParser parser, PrintStream out) {
        this.service = service;
        this.parser = parser;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute(args);
    }

    ExitCode execute(ApplicationArguments args) {
        if (parser.isHelp(args)) {
            out.print(CommandLineParser.usage());
            return args.containsOption(CommandLineParser.HELP) ? ExitCode.SUCCESS : ExitCode.INVALID_ARGUMENTS;
        }
        try {
            SplitRequest request = parser.parse(args);
            SplitResult result = service.split(request);
            printSummary(result, request);
            return ExitCode.SUCCESS;
        } catch (SplitException e) {
            ExitCode code = ExitCode.of(e.reason());
            log.error("{} (exit code {})", e.getMessage(), code.code());
            log.debug("Failure details", e);
            return code;
        } catch (RuntimeException e) {
            log.error("Unexpected failure", e);
            return ExitCode.UNEXPECTED_ERROR;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode.code();
    }

    private void printSummary(SplitResult result, SplitRequest request) {
        out.printf("Output folder : %s%n", result.outputDir());
        out.printf("Encoding      : %s%n", result.encoding());
        out.printf("Limit (X)     : %d %s per file%n", request.settings().maxTransactionsPerFile(),
                request.settings().countedElement());
        out.printf("Totals        : %d parts, %d %s, %d %s%n", result.parts().size(), result.totalPolicies(),
                request.settings().splitElement(), result.totalTransactions(), request.settings().countedElement());
        out.printf("Elapsed       : %d ms%n", result.elapsed().toMillis());
        for (PartSummary part : result.parts()) {
            out.printf("  %-40s %6d policies %8d transactions%s%n", part.fileName(), part.policies(),
                    part.transactions(), part.overLimit() ? "  (single Policy over the limit)" : "");
        }
    }
}
