package com.transunion.xmlsplitter.core;

/**
 * How the document is cut, independent of where it is read from or written to.
 *
 * @param maxTransactionsPerFile X, a soft limit applied at split-element boundaries
 * @param splitElement           local name of the element that is never split (Policy)
 * @param countedElement         local name of the element counted against X, at any depth (Transaction)
 */
public record SplitSettings(
        int maxTransactionsPerFile,
        String splitElement,
        String countedElement,
        HeaderMode headerMode,
        TrailerMode trailerMode) {

    public static final String DEFAULT_SPLIT_ELEMENT = "Policy";
    public static final String DEFAULT_COUNTED_ELEMENT = "Transaction";

    public SplitSettings {
        if (maxTransactionsPerFile < 1) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS,
                    "max-transactions must be at least 1, got " + maxTransactionsPerFile);
        }
        requireName(splitElement, "split-element");
        requireName(countedElement, "counted-element");
        if (splitElement.equals(countedElement)) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS,
                    "split-element and counted-element must differ");
        }
        if (headerMode == null || trailerMode == null) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS, "header-mode and trailer-mode are required");
        }
    }

    public static SplitSettings defaults(int maxTransactionsPerFile) {
        return new SplitSettings(maxTransactionsPerFile, DEFAULT_SPLIT_ELEMENT, DEFAULT_COUNTED_ELEMENT,
                HeaderMode.EVERY_FILE, TrailerMode.LAST_FILE);
    }

    private static void requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS, name + " must not be empty");
        }
    }
}
