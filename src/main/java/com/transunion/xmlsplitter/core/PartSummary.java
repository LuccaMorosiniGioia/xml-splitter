package com.transunion.xmlsplitter.core;

/**
 * What ended up in one output file.
 *
 * @param overLimit true when the part exceeds X, which only happens for a single Policy larger than X
 */
public record PartSummary(String fileName, int policies, long transactions, long bytes, boolean overLimit) {
}
