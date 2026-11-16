package com.transunion.xmlsplitter.core.xml;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.Location;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartDocument;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import com.transunion.xmlsplitter.core.SplitException;
import com.transunion.xmlsplitter.core.SplitSettings;

/**
 * Streams the input as: a {@link DocumentHead}, then one {@link PolicyBlock} at a time, then a
 * {@link DocumentTail}. Events are passed through untouched, so attributes, namespaces, comments and unknown
 * elements are preserved. Split elements are expected as direct children of the root element.
 */
public final class XmlDocumentReader implements AutoCloseable {

    /**
     * Guard for content buffered outside any split element (header, trailer, content between Policies). Hitting
     * it almost always means the split element name is wrong, and buffering further would exhaust the heap.
     */
    static final int MAX_EVENTS_OUTSIDE_SPLIT_ELEMENTS = 1_000_000;

    private static final String DEFAULT_ENCODING = "UTF-8";

    private final InputStream stream;
    private final XMLEventReader events;
    private final String splitElement;
    private final String countedElement;
    private final DocumentHead head;

    private List<XMLEvent> nextLeading;
    private StartElement nextPolicyStart;
    private DocumentTail tail;

    private XmlDocumentReader(InputStream stream, SplitSettings settings) {
        this.stream = stream;
        this.splitElement = settings.splitElement();
        this.countedElement = settings.countedElement();
        String detectedEncoding;
        try {
            XMLStreamReader streamReader = StaxFactories.input().createXMLStreamReader(stream);
            detectedEncoding = streamReader.getEncoding();
            this.events = StaxFactories.input().createXMLEventReader(streamReader);
        } catch (XMLStreamException e) {
            throw malformed(e);
        }
        this.head = readHead(detectedEncoding);
    }

    public static XmlDocumentReader open(Path input, SplitSettings settings) {
        InputStream stream;
        try {
            stream = new BufferedInputStream(Files.newInputStream(input), 1 << 16);
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.INPUT_UNREADABLE, "Cannot read input " + input, e);
        }
        try {
            return new XmlDocumentReader(stream, settings);
        } catch (RuntimeException e) {
            closeQuietly(stream);
            throw e;
        }
    }

    public DocumentHead head() {
        return head;
    }

    /** Next whole Policy, or {@code null} when there are no more (then {@link #tail()} is available). */
    public PolicyBlock nextPolicy() {
        if (nextPolicyStart == null) {
            return null;
        }
        List<XMLEvent> buffer = new ArrayList<>(nextLeading);
        long transactions = readElementInto(nextPolicyStart, buffer);
        advanceToNextPolicy();
        return new PolicyBlock(buffer, transactions);
    }

    public DocumentTail tail() {
        if (tail == null) {
            throw new IllegalStateException("tail() is only available after the last Policy has been read");
        }
        return tail;
    }

    @Override
    public void close() {
        try {
            events.close();
        } catch (XMLStreamException ignored) {
            // the stream is closed below anyway
        }
        closeQuietly(stream);
    }

    /** Output uses the declared encoding, else the one the parser detected (e.g. from a BOM), else UTF-8. */
    private DocumentHead readHead(String detectedEncoding) {
        String encoding = isBlank(detectedEncoding) ? DEFAULT_ENCODING : detectedEncoding;
        String version = "1.0";
        Boolean standalone = null;
        List<XMLEvent> prolog = new ArrayList<>();
        XMLEvent event = next();
        if (event.isStartDocument()) {
            StartDocument declaration = (StartDocument) event;
            if (!isBlank(declaration.getCharacterEncodingScheme())) {
                encoding = declaration.getCharacterEncodingScheme();
            }
            if (declaration.getVersion() != null) {
                version = declaration.getVersion();
            }
            standalone = declaration.standaloneSet() ? declaration.isStandalone() : null;
            event = next();
        }
        while (!event.isStartElement()) {
            if (event.isProcessingInstruction() || event.getEventType() == XMLEvent.COMMENT
                    || event.getEventType() == XMLEvent.DTD) {
                prolog.add(event);
            }
            event = next();
        }
        StartElement root = event.asStartElement();

        Siblings siblings = readSiblings();
        List<XMLEvent> header = siblings.content();
        List<XMLEvent> trailingWhitespace = splitTrailingWhitespace(header);
        if (siblings.policyStart() != null) {
            nextLeading = trailingWhitespace;
            nextPolicyStart = siblings.policyStart();
        } else {
            tail = readTail(siblings.rootEnd(), List.of(), trailingWhitespace);
        }
        return new DocumentHead(encoding, version, standalone, prolog, root, header);
    }

    private void advanceToNextPolicy() {
        Siblings siblings = readSiblings();
        if (siblings.policyStart() != null) {
            nextLeading = siblings.content();
            nextPolicyStart = siblings.policyStart();
            return;
        }
        List<XMLEvent> trailer = siblings.content();
        List<XMLEvent> closingWhitespace = splitTrailingWhitespace(trailer);
        if (trailer.stream().allMatch(XmlDocumentReader::isWhitespace)) {
            trailer.clear();
        }
        nextLeading = null;
        nextPolicyStart = null;
        tail = readTail(siblings.rootEnd(), trailer, closingWhitespace);
    }

    /** Reads root-level content up to the next split element start or the root end tag. */
    private Siblings readSiblings() {
        List<XMLEvent> content = new ArrayList<>();
        int depth = 0;
        while (true) {
            XMLEvent event = next();
            if (event.isStartElement()) {
                if (isSplitElement(event.asStartElement())) {
                    if (depth == 0) {
                        return new Siblings(content, event.asStartElement(), null);
                    }
                    throw new SplitException(SplitException.Reason.MALFORMED_INPUT,
                            "Unsupported structure: <" + splitElement + "> found nested inside another element at "
                                    + describe(event.getLocation()) + "; it must be a direct child of the root element");
                }
                depth++;
            } else if (event.isEndElement()) {
                if (depth == 0) {
                    return new Siblings(content, null, event.asEndElement());
                }
                depth--;
            }
            content.add(event);
            if (content.size() > MAX_EVENTS_OUTSIDE_SPLIT_ELEMENTS) {
                throw new SplitException(SplitException.Reason.MALFORMED_INPUT,
                        "More than " + MAX_EVENTS_OUTSIDE_SPLIT_ELEMENTS + " XML events outside any <" + splitElement
                                + "> element (near " + describe(event.getLocation())
                                + "); check that split-element is correct");
            }
        }
    }

    /** Copies a whole element (start tag already read) into {@code buffer}, counting nested counted elements. */
    private long readElementInto(StartElement start, List<XMLEvent> buffer) {
        buffer.add(start);
        long counted = 0;
        int depth = 1;
        while (depth > 0) {
            XMLEvent event = next();
            buffer.add(event);
            if (event.isStartElement()) {
                depth++;
                if (localName(event.asStartElement()).equals(countedElement)) {
                    counted++;
                }
            } else if (event.isEndElement()) {
                depth--;
            }
        }
        return counted;
    }

    private DocumentTail readTail(EndElement rootEnd, List<XMLEvent> trailer, List<XMLEvent> closingWhitespace) {
        List<XMLEvent> epilog = new ArrayList<>();
        XMLEvent event = next();
        while (!event.isEndDocument()) {
            if (event.isProcessingInstruction() || event.getEventType() == XMLEvent.COMMENT) {
                epilog.add(event);
            }
            event = next();
        }
        return new DocumentTail(trailer, closingWhitespace, rootEnd.getName(), epilog);
    }

    private XMLEvent next() {
        try {
            return events.nextEvent();
        } catch (XMLStreamException e) {
            throw malformed(e);
        }
    }

    private boolean isSplitElement(StartElement element) {
        return localName(element).equals(splitElement);
    }

    private static String localName(StartElement element) {
        return element.getName().getLocalPart();
    }

    /** Removes and returns the whitespace-only character events at the end of {@code events}. */
    private static List<XMLEvent> splitTrailingWhitespace(List<XMLEvent> events) {
        int firstTrailing = events.size();
        while (firstTrailing > 0 && isWhitespace(events.get(firstTrailing - 1))) {
            firstTrailing--;
        }
        List<XMLEvent> trailing = new ArrayList<>(events.subList(firstTrailing, events.size()));
        events.subList(firstTrailing, events.size()).clear();
        return trailing;
    }

    private static boolean isWhitespace(XMLEvent event) {
        return event.isCharacters() && !event.asCharacters().isCData() && event.asCharacters().getData().isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SplitException malformed(XMLStreamException e) {
        return new SplitException(SplitException.Reason.MALFORMED_INPUT,
                "Input is not well-formed XML: " + e.getMessage(), e);
    }

    private static String describe(Location location) {
        return location == null ? "unknown position"
                : "line " + location.getLineNumber() + ", column " + location.getColumnNumber();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing useful to do on a read-only stream
        }
    }

    private record Siblings(List<XMLEvent> content, StartElement policyStart, EndElement rootEnd) {
    }
}
