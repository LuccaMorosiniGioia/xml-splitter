package com.transunion.xmlsplitter.support;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates a large, realistic-looking input: namespaces, a header, a trailer and Policies with rich
 * attributes and nested elements. Transactions per Policy follow the shape described for the real data:
 * usually 1, sometimes a few, rarely close to 100 (and never more than 120).
 *
 * <p>Run standalone: {@code java -cp target/test-classes com.transunion.xmlsplitter.support.LargeXmlGenerator
 * <output.xml> [sizeMb]}.
 */
public final class LargeXmlGenerator {

    public record Stats(long policies, long transactions, long bytes) {
    }

    private LargeXmlGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: LargeXmlGenerator <output.xml> [sizeMb, default 320]");
            System.exit(2);
        }
        long sizeMb = args.length > 1 ? Long.parseLong(args[1]) : 320;
        Stats stats = generate(Path.of(args[0]), sizeMb * 1024 * 1024);
        System.out.printf("Wrote %s: %,d bytes, %,d policies, %,d transactions%n", args[0], stats.bytes(),
                stats.policies(), stats.transactions());
    }

    public static Stats generate(Path file, long targetBytes) throws IOException {
        Random random = new Random(20261116L);
        long policies = 0;
        long transactions = 0;
        try (CountingWriter out = new CountingWriter(new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8), 1 << 20))) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write("<!-- Synthetic load-test input -->\n");
            out.write("<Document xmlns=\"urn:transunion:policies\" xmlns:ext=\"urn:transunion:ext\" "
                    + "batchId=\"LOAD-2026-11\" schemaVersion=\"3.2\" generatedBy=\"LargeXmlGenerator\">\n");
            out.write("  <Header>\n    <ext:Sender code=\"TU-AR\" country=\"AR\">TransUnion Argentina</ext:Sender>\n"
                    + "    <ext:CreatedAt>2026-11-16T12:00:00Z</ext:CreatedAt>\n  </Header>\n");
            while (out.count() < targetBytes) {
                policies++;
                transactions += writePolicy(out, policies, random);
            }
            out.write("  <Trailer policyCount=\"" + policies + "\" transactionCount=\"" + transactions + "\"/>\n");
            out.write("</Document>\n");
        }
        return new Stats(policies, transactions, Files.size(file));
    }

    private static int writePolicy(Writer out, long id, Random random) throws IOException {
        int transactions = transactionCount(random);
        int claims = Math.min(transactions, 1 + random.nextInt(3));
        out.write("  <Policy id=\"POL-" + id + "\" product=\"" + PRODUCTS[random.nextInt(PRODUCTS.length)]
                + "\" status=\"ACTIVE\" startDate=\"2024-0" + (1 + random.nextInt(9)) + "-15\" ext:branch=\"B"
                + random.nextInt(500) + "\">\n");
        out.write("    <Holder documentType=\"DNI\" documentNumber=\"" + (20_000_000 + random.nextInt(30_000_000))
                + "\"><Name>Holder " + id + "</Name><Address street=\"Av. Corrientes " + random.nextInt(9999)
                + "\" city=\"Buenos Aires\" zip=\"C10" + random.nextInt(99) + "\"/></Holder>\n");
        out.write("    <Coverage code=\"COV-" + random.nextInt(50) + "\" premium=\"" + random.nextInt(100_000)
                + ".00\" currency=\"ARS\"><Deductible amount=\"" + random.nextInt(5000) + "\"/></Coverage>\n");
        int written = 0;
        for (int c = 1; c <= claims; c++) {
            int inClaim = c == claims ? transactions - written : transactions / claims;
            out.write("    <Claim id=\"CLM-" + id + "-" + c + "\" openedAt=\"2025-1" + random.nextInt(3)
                    + "-0" + (1 + random.nextInt(9)) + "\" status=\"OPEN\">\n");
            out.write("      <Description>Claim &amp; damage report for policy " + id + "</Description>\n");
            for (int t = 0; t < inClaim; t++) {
                written++;
                out.write("      <Transaction id=\"TRX-" + id + "-" + written + "\" type=\"PAYMENT\" amount=\""
                        + random.nextInt(1_000_000) / 100.0 + "\" currency=\"ARS\" postedAt=\"2026-01-"
                        + (10 + random.nextInt(18)) + "T10:15:00Z\"><Reference>REF" + random.nextLong()
                        + "</Reference><Payee bank=\"0" + random.nextInt(999) + "\" account=\"" + random.nextLong()
                        + "\"/></Transaction>\n");
            }
            out.write("    </Claim>\n");
        }
        out.write("  </Policy>\n");
        return transactions;
    }

    private static final String[] PRODUCTS = {"AUTO", "HOME", "LIFE", "HEALTH", "TRAVEL"};

    /** ~80% one Transaction, ~19% 2 to 10, ~1% 50 to 120. */
    private static int transactionCount(Random random) {
        int roll = random.nextInt(100);
        if (roll < 80) {
            return 1;
        }
        if (roll < 99) {
            return 2 + random.nextInt(9);
        }
        return 50 + random.nextInt(71);
    }

    /** Counts characters written; the generated content is ASCII, so characters equal bytes. */
    private static final class CountingWriter extends Writer {
        private final Writer delegate;
        private long count;

        CountingWriter(Writer delegate) {
            this.delegate = delegate;
        }

        long count() {
            return count;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            delegate.write(buffer, offset, length);
            count += length;
        }

        @Override
        public void write(String text) throws IOException {
            delegate.write(text);
            count += text.length();
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
