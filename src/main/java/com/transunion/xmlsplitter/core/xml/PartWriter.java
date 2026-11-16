package com.transunion.xmlsplitter.core.xml;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import com.transunion.xmlsplitter.core.SplitException;

/**
 * Writes the body of one output part: declaration, prolog, root start tag, optional header and whole Policies.
 * The root element is left open by {@link #finishBody()}; {@link PartSealer} closes it once the trailer is known.
 */
public final class PartWriter {

    private final Path file;
    private final OutputStream stream;
    private final XMLEventWriter writer;
    private int policies;
    private long transactions;

    private PartWriter(Path file, OutputStream stream, XMLEventWriter writer) {
        this.file = file;
        this.stream = stream;
        this.writer = writer;
    }

    public static PartWriter open(Path file, DocumentHead head, boolean includeHeader) {
        OutputStream stream = null;
        try {
            stream = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), 1 << 16);
            XMLEventWriter writer = StaxFactories.output().createXMLEventWriter(stream, head.encoding());
            PartWriter part = new PartWriter(file, stream, writer);
            part.writeStart(head, includeHeader);
            return part;
        } catch (IOException | XMLStreamException | RuntimeException e) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e instanceof SplitException splitException ? splitException : failure(file, e);
        }
    }

    public void write(PolicyBlock policy) {
        try {
            for (XMLEvent event : policy.events()) {
                writer.add(event);
            }
        } catch (XMLStreamException e) {
            throw failure(file, e);
        }
        policies++;
        transactions += policy.transactions();
    }

    /** Flushes and closes the file, leaving the root element open. */
    public void finishBody() {
        try (OutputStream out = stream) {
            // An empty Characters event forces the writer to emit the pending '>' of the last start tag.
            writer.add(StaxFactories.events().createCharacters(""));
            writer.flush();
            writer.close();
        } catch (IOException | XMLStreamException e) {
            throw failure(file, e);
        }
    }

    /** Releases the file handle after a failure, so the staging folder can be deleted (Windows locks open files). */
    public void abort() {
        try {
            stream.close();
        } catch (IOException ignored) {
            // the partial file is about to be deleted with the staging folder
        }
    }

    public Path file() {
        return file;
    }

    public int policies() {
        return policies;
    }

    public long transactions() {
        return transactions;
    }

    private void writeStart(DocumentHead head, boolean includeHeader) throws XMLStreamException {
        XMLEventFactory events = StaxFactories.events();
        writer.add(head.standalone() == null
                ? events.createStartDocument(head.encoding(), head.version())
                : events.createStartDocument(head.encoding(), head.version(), head.standalone()));
        writer.add(events.createSpace("\n"));
        for (XMLEvent event : head.prolog()) {
            writer.add(event);
            writer.add(events.createSpace("\n"));
        }
        writer.add(head.root());
        if (includeHeader) {
            for (XMLEvent event : head.header()) {
                writer.add(event);
            }
        }
    }

    private static SplitException failure(Path file, Exception e) {
        return new SplitException(SplitException.Reason.IO_FAILURE, "Cannot write " + file + ": " + e.getMessage(), e);
    }
}
