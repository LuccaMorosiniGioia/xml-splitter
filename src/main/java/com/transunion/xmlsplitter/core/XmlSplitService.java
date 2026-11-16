package com.transunion.xmlsplitter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transunion.xmlsplitter.core.StreamingSplitter.WrittenPart;
import com.transunion.xmlsplitter.core.StreamingSplitter.WrittenParts;
import com.transunion.xmlsplitter.core.output.ManifestWriter;
import com.transunion.xmlsplitter.core.output.OutputPublisher;
import com.transunion.xmlsplitter.core.packing.PackingStrategy;
import com.transunion.xmlsplitter.core.packing.SequentialGreedyPacking;
import com.transunion.xmlsplitter.core.xml.OutputVerifier;
import com.transunion.xmlsplitter.core.xml.SchemaValidator;

/**
 * Entry point of the core: split, verify, write the manifest and publish atomically. It knows nothing about
 * the command line, so a REST or SOAP front end could call it unchanged.
 */
public class XmlSplitService {

    private static final Logger log = LoggerFactory.getLogger(XmlSplitService.class);

    private final StreamingSplitter splitter;
    private final OutputPublisher publisher;

    public XmlSplitService() {
        this(new SequentialGreedyPacking(), new OutputPublisher());
    }

    public XmlSplitService(PackingStrategy packing, OutputPublisher publisher) {
        this.splitter = new StreamingSplitter(packing);
        this.publisher = publisher;
    }

    public SplitResult split(SplitRequest request) {
        long started = System.nanoTime();
        checkInput(request.input());
        SchemaValidator schema = request.schema() == null ? null : SchemaValidator.load(request.schema());

        Path staging = publisher.prepare(request.outputDir(), request.overwrite());
        try {
            log.info("Splitting {} (max {} {} per file)", request.input(),
                    request.settings().maxTransactionsPerFile(), request.settings().countedElement());
            WrittenParts written = splitter.split(request.input(), staging, request.settings());
            List<PartSummary> parts = verify(written, request.settings(), schema);
            ManifestWriter.write(staging, request.input(), written.encoding(), request.settings(), parts);
            publisher.publish(staging, request.outputDir(), request.overwrite());

            SplitResult result = new SplitResult(request.outputDir().toAbsolutePath().normalize(),
                    written.encoding(), parts,
                    parts.stream().mapToLong(PartSummary::policies).sum(),
                    parts.stream().mapToLong(PartSummary::transactions).sum(),
                    Duration.ofNanos(System.nanoTime() - started));
            log.info("Wrote {} parts ({} policies, {} transactions) to {} in {} ms", parts.size(),
                    result.totalPolicies(), result.totalTransactions(), result.outputDir(), result.elapsed().toMillis());
            return result;
        } catch (RuntimeException e) {
            publisher.discard(staging);
            throw e;
        }
    }

    private static List<PartSummary> verify(WrittenParts written, SplitSettings settings, SchemaValidator schema) {
        OutputVerifier verifier = new OutputVerifier(settings);
        List<PartSummary> parts = new ArrayList<>();
        for (WrittenPart part : written.parts()) {
            verifier.verify(part.file(), part.policies(), part.transactions());
            if (schema != null) {
                schema.validate(part.file());
            }
            parts.add(new PartSummary(part.file().getFileName().toString(), part.policies(), part.transactions(),
                    size(part.file()), part.transactions() > settings.maxTransactionsPerFile()));
        }
        return parts;
    }

    private static void checkInput(Path input) {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
            throw new SplitException(SplitException.Reason.INPUT_UNREADABLE, "Input file not found or not readable: " + input);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE, "Cannot stat " + file, e);
        }
    }
}
