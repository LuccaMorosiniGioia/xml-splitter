package com.transunion.xmlsplitter.core.packing;

/**
 * Decides when the current output file is closed. Kept apart from all I/O so the rule can be swapped
 * (e.g. a hard limit or a reordering strategy) without touching the reader or the writers.
 */
public interface PackingStrategy {

    /**
     * @param policiesInPart     Policies already written to the current part
     * @param transactionsInPart Transactions already written to the current part
     * @param incoming           Transactions of the Policy about to be written
     * @param limit              X
     * @return true to close the current part and write the incoming Policy to a new one
     */
    boolean startNewPart(int policiesInPart, long transactionsInPart, long incoming, long limit);
}
