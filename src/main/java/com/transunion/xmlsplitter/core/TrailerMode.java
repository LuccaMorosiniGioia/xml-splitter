package com.transunion.xmlsplitter.core;

/**
 * Where the non-split siblings that appear after the last split element (the "trailer") are written.
 */
public enum TrailerMode {
    LAST_FILE,
    EVERY_FILE;

    public boolean includeIn(boolean lastPart) {
        return this == EVERY_FILE || lastPart;
    }

    public static TrailerMode parse(String value) {
        return EnumValues.parse(TrailerMode.class, value);
    }

    @Override
    public String toString() {
        return EnumValues.format(this);
    }
}
