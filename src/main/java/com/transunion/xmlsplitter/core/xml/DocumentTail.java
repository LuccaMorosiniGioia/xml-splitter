package com.transunion.xmlsplitter.core.xml;

import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.stream.events.XMLEvent;

/**
 * Everything after the last split element.
 *
 * @param trailer           non-split siblings after the last split element (empty if there are none)
 * @param closingWhitespace whitespace right before the root end tag, kept in every part for readability
 * @param rootName          qualified name of the root element, used to close every part
 * @param epilog            comments and processing instructions after the root element
 */
public record DocumentTail(List<XMLEvent> trailer, List<XMLEvent> closingWhitespace, QName rootName,
        List<XMLEvent> epilog) {

    public DocumentTail {
        trailer = List.copyOf(trailer);
        closingWhitespace = List.copyOf(closingWhitespace);
        epilog = List.copyOf(epilog);
    }

    public String rootEndTag() {
        String prefix = rootName.getPrefix();
        String name = prefix == null || prefix.isEmpty() ? rootName.getLocalPart() : prefix + ":" + rootName.getLocalPart();
        return "</" + name + ">";
    }
}
