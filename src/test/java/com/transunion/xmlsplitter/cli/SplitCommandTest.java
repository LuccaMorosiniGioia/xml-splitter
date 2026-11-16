package com.transunion.xmlsplitter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import com.transunion.xmlsplitter.core.HeaderMode;
import com.transunion.xmlsplitter.core.SplitRequest;
import com.transunion.xmlsplitter.core.TrailerMode;
import com.transunion.xmlsplitter.core.XmlSplitService;
import com.transunion.xmlsplitter.support.Parts;
import com.transunion.xmlsplitter.support.TestXml;

class SplitCommandTest {

    @TempDir
    Path tmp;

    private final SplitterProperties defaults =
            new SplitterProperties(5000, "Policy", "Transaction", "every-file", "last-file", null, false);
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final SplitCommand command = new SplitCommand(new XmlSplitService(), new CommandLineParser(defaults),
            new PrintStream(console, true, StandardCharsets.UTF_8));

    @Test
    void splitsAndReturnsSuccess() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));
        Path out = tmp.resolve("out");

        ExitCode code = run("--input=" + input, "--output=" + out, "--max-transactions=4");

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(Parts.readAll(out)).hasSize(2);
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("2 parts", "3 Policy", "6 Transaction");
    }

    @Test
    void acceptsTheInputAsPositionalArgumentAndDefaultsTheOutputFolder() throws IOException {
        Path input = TestXml.document().policies(1).writeTo(tmp.resolve("batch.xml"));

        assertThat(run(input.toString())).isEqualTo(ExitCode.SUCCESS);
        assertThat(tmp.resolve("batch-parts").resolve("batch_part_0001.xml")).exists();
    }

    @Test
    void commandLineOverridesApplicationProperties() {
        CommandLineParser parser = new CommandLineParser(defaults);

        SplitRequest request = parser.parse(new DefaultApplicationArguments("--input=a.xml", "--max-transactions=7",
                "--header-mode=first-only", "--trailer-mode=every-file", "--overwrite", "--schema=s.xsd"));

        assertThat(request.settings().maxTransactionsPerFile()).isEqualTo(7);
        assertThat(request.settings().headerMode()).isEqualTo(HeaderMode.FIRST_ONLY);
        assertThat(request.settings().trailerMode()).isEqualTo(TrailerMode.EVERY_FILE);
        assertThat(request.overwrite()).isTrue();
        assertThat(request.schema()).isEqualTo(Path.of("s.xsd"));
    }

    @Test
    void usesApplicationPropertiesWhenNoOptionIsGiven() {
        SplitterProperties configured =
                new SplitterProperties(300, "Policy", "Transaction", "first-only", "every-file", "target-dir", true);

        SplitRequest request = new CommandLineParser(configured).parse(new DefaultApplicationArguments("a.xml"));

        assertThat(request.settings().maxTransactionsPerFile()).isEqualTo(300);
        assertThat(request.settings().headerMode()).isEqualTo(HeaderMode.FIRST_ONLY);
        assertThat(request.outputDir()).isEqualTo(Path.of("target-dir"));
        assertThat(request.overwrite()).isTrue();
    }

    @Test
    void invalidArgumentsExitWithCode2() {
        assertThat(run("--input=a.xml", "--max-transactions=0")).isEqualTo(ExitCode.INVALID_ARGUMENTS);
        assertThat(run("--input=a.xml", "--max-transactions=abc")).isEqualTo(ExitCode.INVALID_ARGUMENTS);
        assertThat(run("--input=a.xml", "--header-mode=sometimes")).isEqualTo(ExitCode.INVALID_ARGUMENTS);
        assertThat(run("--input=a.xml", "--unknown=1")).isEqualTo(ExitCode.INVALID_ARGUMENTS);
        assertThat(run()).isEqualTo(ExitCode.INVALID_ARGUMENTS);
    }

    @Test
    void helpExitsWithSuccess() {
        assertThat(run("--help")).isEqualTo(ExitCode.SUCCESS);
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Usage", "--max-transactions", "Exit codes");
    }

    @Test
    void mapsEachFailureToItsExitCode() throws IOException {
        Path malformed = tmp.resolve("bad.xml");
        Files.writeString(malformed, "<Document><Policy>");
        Path valid = TestXml.document().policies(1).writeTo(tmp.resolve("ok.xml"));
        Path out = tmp.resolve("out");

        assertThat(run("--input=" + tmp.resolve("missing.xml"))).isEqualTo(ExitCode.INPUT_UNREADABLE);
        assertThat(run("--input=" + malformed, "--output=" + out)).isEqualTo(ExitCode.MALFORMED_INPUT);
        assertThat(run("--input=" + valid, "--output=" + out)).isEqualTo(ExitCode.SUCCESS);
        assertThat(run("--input=" + valid, "--output=" + out)).isEqualTo(ExitCode.OUTPUT_CONFLICT);
        assertThat(run("--input=" + valid, "--output=" + out, "--overwrite")).isEqualTo(ExitCode.SUCCESS);
    }

    private ExitCode run(String... args) {
        return command.execute(new DefaultApplicationArguments(args));
    }
}
