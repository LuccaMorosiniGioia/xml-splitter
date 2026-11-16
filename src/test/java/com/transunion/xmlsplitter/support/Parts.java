package com.transunion.xmlsplitter.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DOM view of small documents (inputs and output parts) for assertions. Parsing with a different parser than
 * the splitter also double-checks well-formedness independently.
 */
public final class Parts {

    private Parts() {
    }

    public record Part(Path file, Document dom) {

        public Element root() {
            return dom.getDocumentElement();
        }

        public List<Element> policies() {
            return children(root(), "Policy");
        }

        public List<String> policyIds() {
            return policies().stream().map(p -> p.getAttribute("id")).toList();
        }

        public long transactions() {
            return policies().stream().mapToLong(Parts::transactionsIn).sum();
        }

        public boolean hasHeader() {
            return !children(root(), "Header").isEmpty();
        }

        public boolean hasTrailer() {
            return !children(root(), "Trailer").isEmpty();
        }
    }

    public static Part parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setCoalescing(false);
            return new Part(file, factory.newDocumentBuilder().parse(file.toFile()));
        } catch (Exception e) {
            throw new AssertionError(file + " is not well-formed: " + e.getMessage(), e);
        }
    }

    /** All {@code *_part_NNNN.xml} files in the folder, in part order. */
    public static List<Part> readAll(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches(".*_part_\\d+\\.xml"))
                    .sorted()
                    .map(Parts::parse)
                    .toList();
        }
    }

    public static long transactionsIn(Element element) {
        return element.getElementsByTagNameNS("*", "Transaction").getLength();
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }
}
