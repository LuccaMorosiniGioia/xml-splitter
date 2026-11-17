package com.transunion.xmlsplitter.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.transunion.xmlsplitter.support.Parts;
import com.transunion.xmlsplitter.support.Parts.Part;

/** The exact example from the exercise statement, kept in samples/ejemplo-enunciado.xml. */
@ExtendWith(OutputCaptureExtension.class)
class BriefExampleTest {

    private static final Path EXAMPLE = Path.of("samples", "ejemplo-enunciado.xml");

    @TempDir
    Path tmp;

    private final XmlSplitService service = new XmlSplitService();

    @Test
    void withXEqualToFiveEachPolicyGetsItsOwnPart() throws IOException {
        SplitResult result = split(5);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(parts).extracting(Part::policyIds).containsExactly(List.of("P1"), List.of("P2"));
        assertThat(parts).extracting(Part::transactions).containsExactly(5L, 4L);
        assertThat(result.parts()).noneMatch(PartSummary::overLimit);
    }

    @Test
    void withXEqualToFourTheFirstPolicyIsFlaggedAndLogged(CapturedOutput output) throws IOException {
        SplitResult result = split(4);

        assertThat(result.parts()).extracting(PartSummary::transactions).containsExactly(5L, 4L);
        assertThat(result.parts()).extracting(PartSummary::overLimit).containsExactly(true, false);
        assertThat(output).contains("WARN").contains("Part 1 exceeds the limit: Policy P1 has 5 Transaction (X = 4)");
    }

    private SplitResult split(int limit) {
        return service.split(new SplitRequest(EXAMPLE, tmp.resolve("out"), SplitSettings.defaults(limit), false, null));
    }
}
