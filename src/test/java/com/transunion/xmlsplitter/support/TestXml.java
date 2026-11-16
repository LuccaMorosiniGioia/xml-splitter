package com.transunion.xmlsplitter.support;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds small input documents for tests: namespaces, root attributes, header, trailer, comments and Policies
 * with a chosen number of Transactions (optionally nested deeper than directly under Claim).
 */
public final class TestXml {

    private String encoding = "UTF-8";
    private String header = "<Header><meta:Batch>B-1</meta:Batch><Sender code=\"TU\">Tést &amp; Co</Sender></Header>";
    private String trailer = "<Trailer checksum=\"abc\"/>";
    private final List<String> policies = new ArrayList<>();
    private int nextId = 1;

    public static TestXml document() {
        return new TestXml();
    }

    /** Adds one Policy per value, each with that many Transactions directly under a Claim. */
    public TestXml policies(int... transactionCounts) {
        for (int count : transactionCounts) {
            policies.add(policy(nextId++, count, false));
        }
        return this;
    }

    /** Adds a Policy whose Transactions sit two levels below Claim. */
    public TestXml nestedPolicy(int transactions) {
        policies.add(policy(nextId++, transactions, true));
        return this;
    }

    public TestXml header(String header) {
        this.header = header;
        return this;
    }

    public TestXml trailer(String trailer) {
        this.trailer = trailer;
        return this;
    }

    public TestXml encoding(String encoding) {
        this.encoding = encoding;
        return this;
    }

    public String build() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"").append(encoding).append("\"?>\n");
        xml.append("<!-- generated for tests -->\n");
        xml.append("<Document xmlns=\"urn:test:policies\" xmlns:meta=\"urn:test:meta\" batch=\"42\" meta:origin=\"unit\">\n");
        if (header != null) {
            xml.append("  ").append(header).append('\n');
        }
        for (String policy : policies) {
            xml.append(policy);
        }
        if (trailer != null) {
            xml.append("  ").append(trailer).append('\n');
        }
        xml.append("</Document>\n");
        return xml.toString();
    }

    public Path writeTo(Path file) throws IOException {
        Files.writeString(file, build(), Charset.forName(encoding));
        return file;
    }

    private static String policy(int id, int transactions, boolean nested) {
        StringBuilder xml = new StringBuilder();
        xml.append("  <Policy id=\"P-").append(id).append("\" product=\"AUTO\" meta:rev=\"3\">\n");
        xml.append("    <Holder name=\"Holder ").append(id).append("\"/>\n");
        xml.append("    <Claim id=\"C-").append(id).append("\">\n");
        String open = nested ? "<Adjustments><Group>" : "";
        String close = nested ? "</Group></Adjustments>" : "";
        for (int t = 1; t <= transactions; t++) {
            xml.append("      ").append(open)
                    .append("<Transaction id=\"T-").append(id).append('-').append(t).append("\" amount=\"").append(t)
                    .append(".50\"><Note><![CDATA[a < b]]></Note></Transaction>").append(close).append('\n');
        }
        xml.append("    </Claim>\n");
        xml.append("  </Policy>\n");
        return xml.toString();
    }
}
