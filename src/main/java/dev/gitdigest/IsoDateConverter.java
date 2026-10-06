package dev.gitdigest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import picocli.CommandLine;

/**
 * Reads a --since or --until date, and says what it wanted when it cannot.
 *
 * <p>picocli's built-in conversion reports a bad value with the Java exception
 * behind it - a class name and a parse index - which tells a user nothing they
 * can act on. {@link LocalDate#parse} is strict, so an impossible day such as
 * 2026-02-30 is refused here too rather than quietly rolled over.
 */
public final class IsoDateConverter implements CommandLine.ITypeConverter<LocalDate> {

    @Override
    public LocalDate convert(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new CommandLine.TypeConversionException(
                    "'" + value + "' is not a date; expected YYYY-MM-DD, e.g. 2026-10-05");
        }
    }
}
