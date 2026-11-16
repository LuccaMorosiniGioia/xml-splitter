package com.transunion.xmlsplitter.cli;

import com.transunion.xmlsplitter.core.SplitException;

/**
 * Process exit codes, so scripts and schedulers can react without parsing the log.
 */
public enum ExitCode {
    SUCCESS(0, "split completed and published"),
    UNEXPECTED_ERROR(1, "unexpected error (bug or environment problem)"),
    INVALID_ARGUMENTS(2, "invalid arguments, configuration or schema file"),
    INPUT_UNREADABLE(3, "input file missing or unreadable"),
    MALFORMED_INPUT(4, "input is not well-formed XML or has an unsupported structure"),
    INVALID_OUTPUT(5, "an output part failed the well-formedness, count or XSD check"),
    OUTPUT_CONFLICT(6, "output folder exists and --overwrite was not given (or it is not ours)"),
    IO_FAILURE(7, "error writing or publishing the output");

    private final int code;
    private final String description;

    ExitCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static ExitCode of(SplitException.Reason reason) {
        return switch (reason) {
            case INVALID_ARGUMENTS -> INVALID_ARGUMENTS;
            case INPUT_UNREADABLE -> INPUT_UNREADABLE;
            case MALFORMED_INPUT -> MALFORMED_INPUT;
            case INVALID_OUTPUT -> INVALID_OUTPUT;
            case OUTPUT_CONFLICT -> OUTPUT_CONFLICT;
            case IO_FAILURE -> IO_FAILURE;
        };
    }
}
