package com.transunion.xmlsplitter.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transunion.xmlsplitter.core.packing.PackingStrategy;
import com.transunion.xmlsplitter.core.xml.DocumentHead;
import com.transunion.xmlsplitter.core.xml.DocumentTail;
import com.transunion.xmlsplitter.core.xml.PartSealer;
import com.transunion.xmlsplitter.core.xml.PartWriter;
import com.transunion.xmlsplitter.core.xml.PolicyBlock;
import com.transunion.xmlsplitter.core.xml.XmlDocumentReader;

/**
 * One pass over the input: reads whole Policies in order and writes them into numbered part files inside
 * {@code targetDir}, letting the {@link PackingStrategy} decide when to start a new part.
 */
class StreamingSplitter {

    private static final Logger log = LoggerFactory.getLogger(StreamingSplitter.class);

    private final PackingStrategy packing;

    StreamingSplitter(PackingStrategy packing) {
        this.packing = packing;
    }

    record WrittenParts(String encoding, List<WrittenPart> parts) {
    }

    record WrittenPart(Path file, int policies, long transactions) {
    }

    WrittenParts split(Path input, Path targetDir, SplitSettings settings) {
        String baseName = SplitRequest.baseName(input);
        List<WrittenPart> parts = new ArrayList<>();
        try (XmlDocumentReader reader = XmlDocumentReader.open(input, settings)) {
            DocumentHead head = reader.head();
            PartWriter current = null;
            try {
                PolicyBlock policy;
                while ((policy = reader.nextPolicy()) != null) {
                    if (current != null && packing.startNewPart(current.policies(), current.transactions(),
                            policy.transactions(), settings.maxTransactionsPerFile())) {
                        PartWriter full = current;
                        current = null;
                        parts.add(finish(full));
                    }
                    if (current == null) {
                        current = openPart(targetDir, baseName, parts.size() + 1, head, settings);
                    }
                    current.write(policy);
                }
                if (current == null) {
                    // No Policies at all: still produce one part holding the root, header and trailer.
                    current = openPart(targetDir, baseName, 1, head, settings);
                }
                PartWriter lastPart = current;
                current = null;
                parts.add(finish(lastPart));
            } catch (RuntimeException e) {
                if (current != null) {
                    current.abort();
                }
                throw e;
            }

            DocumentTail tail = reader.tail();
            for (int i = 0; i < parts.size(); i++) {
                boolean last = i == parts.size() - 1;
                PartSealer.seal(parts.get(i).file(), head.encoding(), tail, settings.trailerMode().includeIn(last));
            }
            return new WrittenParts(head.encoding(), parts);
        }
    }

    private static PartWriter openPart(Path dir, String baseName, int index, DocumentHead head, SplitSettings settings) {
        Path file = dir.resolve(String.format("%s_part_%04d.xml", baseName, index));
        return PartWriter.open(file, head, settings.headerMode().includeIn(index));
    }

    private static WrittenPart finish(PartWriter part) {
        part.finishBody();
        log.debug("Wrote {} ({} policies, {} transactions)", part.file().getFileName(), part.policies(),
                part.transactions());
        return new WrittenPart(part.file(), part.policies(), part.transactions());
    }
}
