package com.transunion.xmlsplitter.core.xml;

import java.util.List;

import javax.xml.stream.events.XMLEvent;

/**
 * One whole split element (Policy), buffered so its Transactions can be counted before deciding which part
 * it goes to. Memory is therefore bounded by the largest single Policy, never by the file size.
 *
 * @param events       the Policy's events, preceded by whatever sat between it and the previous Policy
 *                     (whitespace, comments, interleaved non-Policy siblings), which travels with it
 * @param transactions counted elements found at any depth inside the Policy
 */
public record PolicyBlock(List<XMLEvent> events, long transactions) {
}
