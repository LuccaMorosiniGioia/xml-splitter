package com.transunion.xmlsplitter.core.packing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequentialGreedyPackingTest {

    private final SequentialGreedyPacking packing = new SequentialGreedyPacking();

    @Test
    void keepsFillingWhileThePolicyFits() {
        assertThat(packing.startNewPart(3, 4_900, 100, 5_000)).isFalse();
    }

    @Test
    void closesThePartBeforeAPolicyThatWouldOverflow() {
        assertThat(packing.startNewPart(3, 4_950, 51, 5_000)).isTrue();
    }

    @Test
    void neverClosesAnEmptyPartEvenForAnOversizedPolicy() {
        assertThat(packing.startNewPart(0, 0, 7_000, 5_000)).isFalse();
    }

    @Test
    void anOversizedPolicyIsFollowedByANewPart() {
        assertThat(packing.startNewPart(1, 7_000, 1, 5_000)).isTrue();
    }

    @Test
    void policiesWithoutTransactionsNeverForceANewPart() {
        assertThat(packing.startNewPart(10, 5_000, 0, 5_000)).isFalse();
    }
}
