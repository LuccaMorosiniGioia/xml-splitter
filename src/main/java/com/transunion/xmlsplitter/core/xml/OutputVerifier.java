package com.transunion.xmlsplitter.core.xml;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.transunion.xmlsplitter.core.SplitException;
import com.transunion.xmlsplitter.core.SplitSettings;

/**
 * Re-reads a written part (streaming, so memory stays flat) to prove it is well-formed and holds exactly the
 * Policies and Transactions the splitter believes it wrote.
 */
public final class OutputVerifier {

    private final SplitSettings settings;

    public OutputVerifier(SplitSettings settings) {
        this.settings = settings;
    }

    public void verify(Path part, int expectedPolicies, long expectedTransactions) {
        int policies = 0;
        long transactions = 0;
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(part), 1 << 16)) {
            XMLStreamReader reader = StaxFactories.input().createXMLStreamReader(stream);
            try {
                int depth = 0;
                int policyDepth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String name = reader.getLocalName();
                        if (policyDepth == 0 && depth == 2 && name.equals(settings.splitElement())) {
                            policies++;
                            policyDepth = depth;
                        } else if (policyDepth > 0 && name.equals(settings.countedElement())) {
                            transactions++;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == policyDepth) {
                            policyDepth = 0;
                        }
                        depth--;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw invalid(part, "not well-formed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE, "Cannot re-read " + part, e);
        }
        if (policies != expectedPolicies || transactions != expectedTransactions) {
            throw invalid(part, "expected " + expectedPolicies + " " + settings.splitElement() + " / "
                    + expectedTransactions + " " + settings.countedElement() + ", found " + policies + " / "
                    + transactions, null);
        }
    }

    private static SplitException invalid(Path part, String detail, Exception cause) {
        return new SplitException(SplitException.Reason.INVALID_OUTPUT,
                "Output " + part.getFileName() + " is invalid: " + detail, cause);
    }
}
