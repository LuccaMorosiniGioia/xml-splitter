package com.transunion.xmlsplitter.core.xml;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import com.transunion.xmlsplitter.core.SplitException;

/**
 * Appends the end of a part: optional trailer, root end tag and epilog.
 *
 * <p>Sealing is deferred until the whole input has been read because the trailer is only known at the end and,
 * with {@code trailer-mode=every-file}, earlier parts need it too. Parts live in the staging folder meanwhile,
 * so nobody sees them unsealed.
 */
public final class PartSealer {

    private PartSealer() {
    }

    public static void seal(Path file, String encoding, DocumentTail tail, boolean includeTrailer) {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.APPEND), appendCharset(encoding)))) {
            XMLEventWriter writer = StaxFactories.output().createXMLEventWriter(out);
            if (includeTrailer) {
                addAll(writer, tail.trailer());
            }
            addAll(writer, tail.closingWhitespace());
            writer.flush();
            // Written as text: this writer never saw the root start tag, so it cannot emit its end event.
            out.write(tail.rootEndTag());
            for (XMLEvent event : tail.epilog()) {
                out.write('\n');
                writer.add(event);
                writer.flush();
            }
            out.write('\n');
            writer.close();
        } catch (IOException | XMLStreamException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE, "Cannot finish " + file + ": " + e.getMessage(), e);
        }
    }

    /** Appending with plain "UTF-16" would insert a second byte order mark in the middle of the file. */
    static Charset appendCharset(String encoding) {
        Charset charset = Charset.forName(encoding);
        return charset.equals(StandardCharsets.UTF_16) ? StandardCharsets.UTF_16BE : charset;
    }

    private static void addAll(XMLEventWriter writer, Iterable<XMLEvent> events) throws XMLStreamException {
        for (XMLEvent event : events) {
            writer.add(event);
        }
    }
}
