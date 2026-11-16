package com.transunion.xmlsplitter.core.xml;

import java.util.List;

import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * Everything before the first split element; it is reproduced at the top of every output part.
 *
 * @param encoding   input encoding, reused for every output
 * @param standalone {@code null} when the input declaration has no standalone attribute
 * @param prolog     comments, processing instructions and DOCTYPE before the root element
 * @param root       root start element with all its attributes and namespace declarations
 * @param header     non-split siblings before the first split element
 */
public record DocumentHead(
        String encoding,
        String version,
        Boolean standalone,
        List<XMLEvent> prolog,
        StartElement root,
        List<XMLEvent> header) {

    public DocumentHead {
        prolog = List.copyOf(prolog);
        header = List.copyOf(header);
    }
}
