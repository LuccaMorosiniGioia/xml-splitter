package com.transunion.xmlsplitter.core;

/**
 * Where the non-split siblings that appear before the first split element (the "header") are written.
 */
public enum HeaderMode {
    EVERY_FILE,
    FIRST_ONLY;

    public boolean includeIn(int partIndex) {
        return this == EVERY_FILE || partIndex == 1;
    }

    public static HeaderMode parse(String value) {
        return EnumValues.parse(HeaderMode.class, value);
    }

    @Override
    public String toString() {
        return EnumValues.format(this);
    }
}
