package com.transunion.xmlsplitter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.transunion.xmlsplitter.support.Parts;
import com.transunion.xmlsplitter.support.TestXml;

/** All-or-nothing behaviour: staging, atomic publish, overwrite rules and cleanup after failures. */
class OutputSafetyTest {

    @TempDir
    Path tmp;

    private final XmlSplitService service = new XmlSplitService();

    @Test
    void refusesToReplaceExistingOutputWithoutOverwrite() throws IOException {
        Path input = TestXml.document().policies(1, 1).writeTo(tmp.resolve("in.xml"));
        Path out = tmp.resolve("out");
        split(input, out, 1, false);
        List<String> before = fileNames(out);

        assertThatThrownBy(() -> split(input, out, 2, false))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.OUTPUT_CONFLICT));
        assertThat(fileNames(out)).isEqualTo(before);
        assertNoLeftovers();
    }

    @Test
    void overwriteReplacesThePreviousRunCompletely() throws IOException {
        Path input = TestXml.document().policies(1, 1, 1, 1).writeTo(tmp.resolve("in.xml"));
        Path out = tmp.resolve("out");
        split(input, out, 1, false);
        assertThat(Parts.readAll(out)).hasSize(4);

        split(input, out, 10, true);

        assertThat(Parts.readAll(out)).hasSize(1);
        assertThat(fileNames(out)).containsExactly("_SUCCESS", "in_part_0001.xml", "manifest.json");
        assertNoLeftovers();
    }

    @Test
    void overwriteRefusesAFolderThatWasNotProducedByTheTool() throws IOException {
        Path input = TestXml.document().policies(1).writeTo(tmp.resolve("in.xml"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("important.txt"), "keep me");

        assertThatThrownBy(() -> split(input, out, 1, true))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.OUTPUT_CONFLICT));
        assertThat(out.resolve("important.txt")).hasContent("keep me");
    }

    @Test
    void anEmptyExistingFolderIsUsedWithoutOverwrite() throws IOException {
        Path input = TestXml.document().policies(1).writeTo(tmp.resolve("in.xml"));
        Path out = Files.createDirectories(tmp.resolve("out"));

        split(input, out, 1, false);

        assertThat(Parts.readAll(out)).hasSize(1);
    }

    @Test
    void malformedInputLeavesNoPartialOutput() throws IOException {
        String valid = TestXml.document().policies(1, 1, 1, 1, 1, 1).build();
        // Truncated in the middle of the last Policy, after several parts have already been written.
        Path input = tmp.resolve("broken.xml");
        Files.writeString(input, valid.substring(0, valid.lastIndexOf("<Policy")) + "<Policy id=\"x\"><Claim>");
        Path out = tmp.resolve("out");

        assertThatThrownBy(() -> split(input, out, 1, false))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.MALFORMED_INPUT));
        assertThat(out).doesNotExist();
        assertNoLeftovers();
    }

    @Test
    void aFailedOverwriteKeepsThePreviousOutput() throws IOException {
        Path good = TestXml.document().policies(1, 1).writeTo(tmp.resolve("good.xml"));
        Path broken = tmp.resolve("broken.xml");
        Files.writeString(broken, "<Document><Policy id=\"1\"><Transaction/></Policy><Policy>");
        Path out = tmp.resolve("out");
        split(good, out, 1, false);
        List<String> before = fileNames(out);

        assertThatThrownBy(() -> split(broken, out, 1, true)).isInstanceOf(SplitException.class);

        assertThat(fileNames(out)).isEqualTo(before);
        assertNoLeftovers();
    }

    @Test
    void missingInputIsReportedBeforeAnythingIsCreated() {
        Path out = tmp.resolve("out");

        assertThatThrownBy(() -> split(tmp.resolve("missing.xml"), out, 1, false))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.INPUT_UNREADABLE));
        assertThat(out).doesNotExist();
    }

    private void split(Path input, Path out, int limit, boolean overwrite) {
        service.split(new SplitRequest(input, out, SplitSettings.defaults(limit), overwrite, null));
    }

    /** No staging or moved-aside folders may survive a run. */
    private void assertNoLeftovers() throws IOException {
        try (Stream<Path> entries = Files.list(tmp)) {
            assertThat(entries.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("."));
        }
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
