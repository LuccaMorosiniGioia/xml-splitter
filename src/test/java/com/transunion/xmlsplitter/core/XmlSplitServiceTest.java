package com.transunion.xmlsplitter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import com.transunion.xmlsplitter.core.output.ManifestWriter;
import com.transunion.xmlsplitter.support.Parts;
import com.transunion.xmlsplitter.support.Parts.Part;
import com.transunion.xmlsplitter.support.TestXml;

class XmlSplitServiceTest {

    @TempDir
    Path tmp;

    private final XmlSplitService service = new XmlSplitService();

    @Test
    void everyPartRespectsTheLimit() throws IOException {
        Path input = TestXml.document().policies(3, 1, 4, 2, 5, 1, 1, 3, 2, 4).writeTo(tmp.resolve("in.xml"));

        SplitResult result = split(input, 6);

        List<Part> parts = Parts.readAll(result.outputDir());
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts).allSatisfy(part -> assertThat(part.transactions()).isLessThanOrEqualTo(6));
        assertThat(result.parts()).noneMatch(PartSummary::overLimit);
    }

    @Test
    void closesThePartBeforeAPolicyThatWouldOverflowIt() throws IOException {
        Path input = TestXml.document().policies(3, 2, 2).writeTo(tmp.resolve("in.xml"));

        List<Part> parts = Parts.readAll(split(input, 5).outputDir());

        assertThat(parts).extracting(Part::policyIds)
                .containsExactly(List.of("P-1", "P-2"), List.of("P-3"));
    }

    @Test
    void policiesAreNeverSplitAndArriveIdentical() throws IOException {
        Path input = TestXml.document().policies(2, 3, 1, 4, 2).writeTo(tmp.resolve("in.xml"));

        List<Part> parts = Parts.readAll(split(input, 4).outputDir());

        List<Element> original = Parts.parse(input).policies();
        List<Element> written = parts.stream().flatMap(part -> part.policies().stream()).toList();
        assertThat(written).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            assertThat(written.get(i).isEqualNode(original.get(i)))
                    .as("Policy %s is copied whole and unchanged", original.get(i).getAttribute("id"))
                    .isTrue();
        }
    }

    @Test
    void anOversizedPolicyGoesAloneInItsOwnPart() throws IOException {
        Path input = TestXml.document().policies(2, 9, 1, 1).writeTo(tmp.resolve("in.xml"));

        SplitResult result = split(input, 5);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(parts).extracting(Part::policyIds)
                .containsExactly(List.of("P-1"), List.of("P-2"), List.of("P-3", "P-4"));
        assertThat(result.parts()).extracting(PartSummary::overLimit).containsExactly(false, true, false);
    }

    @Test
    void totalsAndPolicyIdsMatchTheInputInOrder() throws IOException {
        Path input = TestXml.document().policies(1, 1, 2, 3, 1, 1, 1, 4, 1, 2, 2, 1).nestedPolicy(3)
                .writeTo(tmp.resolve("in.xml"));
        Part source = Parts.parse(input);

        SplitResult result = split(input, 5);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(parts.stream().flatMap(part -> part.policyIds().stream()).toList())
                .containsExactlyElementsOf(source.policyIds());
        assertThat(parts.stream().mapToLong(Part::transactions).sum()).isEqualTo(source.transactions());
        assertThat(result.totalPolicies()).isEqualTo(source.policies().size());
        assertThat(result.totalTransactions()).isEqualTo(source.transactions());
    }

    @Test
    void countsTransactionsAtAnyDepthInsideAPolicy() throws IOException {
        Path input = TestXml.document().nestedPolicy(3).nestedPolicy(3).writeTo(tmp.resolve("in.xml"));

        SplitResult result = split(input, 5);

        assertThat(result.totalTransactions()).isEqualTo(6);
        assertThat(result.parts()).extracting(PartSummary::transactions).containsExactly(3L, 3L);
    }

    @Test
    void filesAreReasonablyFull() throws IOException {
        int[] ones = new int[1_000];
        java.util.Arrays.fill(ones, 1);
        Path input = TestXml.document().policies(ones).writeTo(tmp.resolve("in.xml"));

        SplitResult result = split(input, 100);

        assertThat(result.parts()).hasSize(10);
    }

    @Test
    void everyPartIsWellFormedAndKeepsRootAttributesAndNamespaces() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));

        List<Part> parts = Parts.readAll(split(input, 2).outputDir());

        assertThat(parts).hasSize(3).allSatisfy(part -> {
            Element root = part.root();
            assertThat(root.getNamespaceURI()).isEqualTo("urn:test:policies");
            assertThat(root.getAttribute("batch")).isEqualTo("42");
            assertThat(root.getAttributeNS("urn:test:meta", "origin")).isEqualTo("unit");
        });
    }

    @Test
    void headerInEveryPartAndTrailerOnlyInTheLastByDefault() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));

        List<Part> parts = Parts.readAll(split(input, 2).outputDir());

        assertThat(parts).extracting(Part::hasHeader).containsExactly(true, true, true);
        assertThat(parts).extracting(Part::hasTrailer).containsExactly(false, false, true);
    }

    @Test
    void headerFirstOnlyAndTrailerEveryFileAreConfigurable() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));
        SplitSettings settings = new SplitSettings(2, "Policy", "Transaction", HeaderMode.FIRST_ONLY,
                TrailerMode.EVERY_FILE);

        SplitResult result = service.split(new SplitRequest(input, tmp.resolve("out"), settings, false, null));
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(parts).extracting(Part::hasHeader).containsExactly(true, false, false);
        assertThat(parts).extracting(Part::hasTrailer).containsExactly(true, true, true);
    }

    @Test
    void limitOfOneWritesOnePolicyPerPart() throws IOException {
        Path input = TestXml.document().policies(1, 1, 1, 2, 1).writeTo(tmp.resolve("in.xml"));

        SplitResult result = split(input, 1);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(parts).hasSize(5).allSatisfy(part -> assertThat(part.policies()).hasSize(1));
        assertThat(result.parts()).extracting(PartSummary::overLimit).containsExactly(false, false, false, true, false);
    }

    @Test
    void documentWithoutPoliciesProducesOnePartWithHeaderAndTrailer() throws IOException {
        Path input = TestXml.document().writeTo(tmp.resolve("in.xml"));

        List<Part> parts = Parts.readAll(split(input, 5).outputDir());

        assertThat(parts).singleElement().satisfies(part -> {
            assertThat(part.hasHeader()).isTrue();
            assertThat(part.hasTrailer()).isTrue();
            assertThat(part.policies()).isEmpty();
        });
    }

    @Test
    void preservesTheInputEncoding() throws IOException {
        Path input = TestXml.document().encoding("ISO-8859-1").policies(1, 1).writeTo(tmp.resolve("latin1.xml"));

        SplitResult result = split(input, 1);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(result.encoding()).isEqualToIgnoringCase("ISO-8859-1");
        for (Part part : parts) {
            String head = Files.readString(part.file(), StandardCharsets.ISO_8859_1);
            assertThat(head).containsIgnoringCase("encoding='ISO-8859-1'").contains("Tést &amp; Co");
        }
    }

    @Test
    void preservesADeclaredUtf16Encoding() throws IOException {
        Path input = TestXml.document().encoding("UTF-16").policies(1, 1).writeTo(tmp.resolve("utf16.xml"));

        SplitResult result = split(input, 1);
        List<Part> parts = Parts.readAll(result.outputDir());

        assertThat(result.encoding()).isEqualToIgnoringCase("UTF-16");
        assertThat(parts).hasSize(2).allSatisfy(part ->
                assertThat(part.root().getTextContent()).contains("Tést & Co"));
        assertThat(parts.get(1).hasTrailer()).isTrue();
    }

    @Test
    void detectsUtf16FromTheByteOrderMarkWhenThereIsNoDeclaration() throws IOException {
        String withDeclaration = TestXml.document().policies(1, 1).build();
        Path input = tmp.resolve("bom.xml");
        Files.writeString(input, withDeclaration.substring(withDeclaration.indexOf('\n') + 1), StandardCharsets.UTF_16);

        SplitResult result = split(input, 1);

        assertThat(result.encoding()).startsWithIgnoringCase("UTF-16");
        assertThat(Parts.readAll(result.outputDir())).hasSize(2).allSatisfy(part ->
                assertThat(part.root().getTextContent()).contains("Tést & Co"));
    }

    @Test
    void copiesCommentsAndProcessingInstructionsAfterTheRootIntoEveryPart() throws IOException {
        Path input = tmp.resolve("epilog.xml");
        Files.writeString(input, TestXml.document().policies(1, 1).build() + "<!-- end of batch -->\n<?audit done?>\n");

        List<Part> parts = Parts.readAll(split(input, 1).outputDir());

        assertThat(parts).hasSize(2).allSatisfy(part -> {
            String text = Files.readString(part.file());
            assertThat(text.substring(text.indexOf("</Document>"))).contains("<!-- end of batch -->", "<?audit done?>");
        });
    }

    @Test
    void writesManifestAndSuccessMarker() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));

        Path out = split(input, 4).outputDir();

        assertThat(out.resolve(ManifestWriter.SUCCESS_FILE)).exists();
        assertThat(Files.readString(out.resolve(ManifestWriter.MANIFEST_FILE)))
                .contains("\"totalPolicies\": 3", "\"totalTransactions\": 6", "in_part_0001.xml", "in_part_0002.xml");
    }

    @Test
    void validatesEveryPartAgainstAnOptionalSchema() throws IOException {
        Path input = TestXml.document().policies(2, 2, 2).writeTo(tmp.resolve("in.xml"));
        Path schema = copyResource("policies.xsd");

        SplitResult result = service.split(new SplitRequest(input, tmp.resolve("out"), SplitSettings.defaults(2),
                false, schema));

        assertThat(result.parts()).hasSize(3);
    }

    @Test
    void failsWhenAPartDoesNotMatchTheSchema() throws IOException {
        Path input = TestXml.document().header("<Unexpected/>").policies(1).writeTo(tmp.resolve("in.xml"));
        Path schema = copyResource("policies.xsd");

        assertThatThrownBy(() -> service.split(new SplitRequest(input, tmp.resolve("out"), SplitSettings.defaults(2),
                false, schema)))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.INVALID_OUTPUT));
        assertThat(tmp.resolve("out")).doesNotExist();
    }

    @Test
    void rejectsPoliciesNestedInsideAnotherElement() throws IOException {
        Path input = tmp.resolve("nested.xml");
        Files.writeString(input, "<Document><Policies><Policy id=\"1\"/></Policies></Document>");

        assertThatThrownBy(() -> split(input, 5))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.MALFORMED_INPUT))
                .hasMessageContaining("direct child of the root");
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThatThrownBy(() -> SplitSettings.defaults(0))
                .isInstanceOfSatisfying(SplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(SplitException.Reason.INVALID_ARGUMENTS));
    }

    private SplitResult split(Path input, int limit) {
        return service.split(new SplitRequest(input, tmp.resolve("out"), SplitSettings.defaults(limit), false, null));
    }

    private Path copyResource(String name) throws IOException {
        Path target = tmp.resolve(name);
        try (var in = getClass().getResourceAsStream("/" + name)) {
            Files.copy(in, target);
        }
        return target;
    }
}
