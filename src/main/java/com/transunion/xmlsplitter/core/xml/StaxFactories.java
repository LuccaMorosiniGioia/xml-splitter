package com.transunion.xmlsplitter.core.xml;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;

import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.XMLOutputFactory2;

import com.ctc.wstx.api.WstxOutputProperties;
import com.ctc.wstx.stax.WstxEventFactory;
import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;

/**
 * Shared, configured StAX factories. Woodstox is used explicitly instead of the JDK implementation because it
 * keeps attribute order and reports CDATA sections, so the parts stay as close as possible to the input.
 */
public final class StaxFactories {

    private static final XMLInputFactory INPUT = createInputFactory();
    private static final XMLOutputFactory OUTPUT = createOutputFactory();
    private static final XMLEventFactory EVENTS = new WstxEventFactory();

    private StaxFactories() {
    }

    public static XMLInputFactory input() {
        return INPUT;
    }

    public static XMLOutputFactory output() {
        return OUTPUT;
    }

    public static XMLEventFactory events() {
        return EVENTS;
    }

    private static XMLInputFactory createInputFactory() {
        XMLInputFactory factory = new WstxInputFactory();
        // No DTD processing and no external entities: protects against XXE and entity expansion attacks.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory2.P_REPORT_CDATA, true);
        factory.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, true);
        return factory;
    }

    private static XMLOutputFactory createOutputFactory() {
        XMLOutputFactory factory = new WstxOutputFactory();
        factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, false);
        factory.setProperty(XMLOutputFactory2.P_AUTOMATIC_EMPTY_ELEMENTS, true);
        // Parts are written as fragments (the root is closed by PartSealer), so the writer must not insist on a
        // complete document. Every part is re-parsed afterwards by OutputVerifier instead.
        factory.setProperty(WstxOutputProperties.P_OUTPUT_VALIDATE_STRUCTURE, false);
        factory.setProperty(WstxOutputProperties.P_AUTOMATIC_END_ELEMENTS, false);
        return factory;
    }
}
