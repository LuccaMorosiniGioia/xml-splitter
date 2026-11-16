package com.transunion.xmlsplitter.core.packing;

/**
 * Fills each file with whole Policies, in input order, until the next one would overflow X.
 *
 * <p>Input order is preserved on purpose. A Policy rarely has more than ~100 Transactions while X is around
 * 5,000, so a file closed early wastes at most ~2% of its capacity; reordering Policies (bin packing) could
 * save at most that much, at the cost of traceability (part N holds a contiguous slice of the input that can
 * be compared with the source) and of having to hold or re-read Policies out of order. It is not worth it.
 *
 * <p>The limit is exceeded only when a single Policy has more than X Transactions: it cannot be split, so it
 * is written alone into its own file (the part is empty when it arrives, and the next Policy always overflows).
 */
public class SequentialGreedyPacking implements PackingStrategy {

    @Override
    public boolean startNewPart(int policiesInPart, long transactionsInPart, long incoming, long limit) {
        return policiesInPart > 0 && transactionsInPart + incoming > limit;
    }
}
