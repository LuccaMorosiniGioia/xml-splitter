package com.transunion.xmlsplitter.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Converts between enum constants and their kebab-case form used in configuration ({@code every-file}).
 */
final class EnumValues {

    private EnumValues() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        String normalized = value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants()).map(EnumValues::format).collect(Collectors.joining(", "));
        throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS,
                "Invalid value '" + value + "'. Allowed: " + allowed);
    }

    static String format(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
