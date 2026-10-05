package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Small, dependency-free tools for things models are unreliable at: knowing what "now" is
 * ({@code current_datetime}), calendar arithmetic ({@code date_calculate}) and exact arithmetic
 * ({@code calculate}). None touch user data. Register with
 * {@code registry.registerAll(UtilityTools.all())}.
 */
public final class UtilityTools {

    public static final String CURRENT_DATETIME = "current_datetime";
    public static final String DATE_CALCULATE = "date_calculate";
    public static final String CALCULATE = "calculate";

    private UtilityTools() {
    }

    public static List<RegisteredTool> all() {
        return all(Clock.systemUTC());
    }

    /** With an explicit clock — for tests, or to pin "now". */
    public static List<RegisteredTool> all(Clock clock) {
        return List.of(currentDateTime(clock), dateCalculate(clock), calculate());
    }

    public static RegisteredTool currentDateTime(Clock clock) {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CURRENT_DATETIME)
                        .description("Get the current date and time, optionally in a specific timezone. Use this "
                                + "whenever the answer depends on today's date or the time.")
                        .parameters(ToolSchemas.object(List.of(),
                                "timezone", ToolSchemas.string("IANA timezone, e.g. \"Europe/London\" or \"America/New_York\". Defaults to UTC.")))
                        .build(),
                (args, context) -> {
                    ZoneId zone = zone(ToolArguments.optionalString(args, "timezone"));
                    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
                    return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            + " (" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                            + ", " + zone.getId() + ")";
                });
    }

    public static RegisteredTool dateCalculate(Clock clock) {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(DATE_CALCULATE)
                        .description("Calendar arithmetic. Either add an amount of time to a date (e.g. a deadline 30 "
                                + "business days from now), or count the days between two dates. Business days are "
                                + "Monday-Friday; public holidays are not taken into account.")
                        .parameters(ToolSchemas.object(List.of(),
                                "start", ToolSchemas.string("Start date, YYYY-MM-DD. Defaults to today."),
                                "end", ToolSchemas.string("End date, YYYY-MM-DD, to count the days between start and end."),
                                "add_days", ToolSchemas.integer("Calendar days to add (negative to subtract)."),
                                "add_business_days", ToolSchemas.integer("Business days (Mon-Fri) to add (negative to subtract)."),
                                "add_weeks", ToolSchemas.integer("Weeks to add."),
                                "add_months", ToolSchemas.integer("Months to add; the day is clamped to the month's end if needed."),
                                "add_years", ToolSchemas.integer("Years to add."),
                                "timezone", ToolSchemas.string("IANA timezone deciding what \"today\" is. Defaults to UTC.")))
                        .build(),
                (args, context) -> {
                    ZoneId zone = zone(ToolArguments.optionalString(args, "timezone"));
                    LocalDate start = date(ToolArguments.optionalString(args, "start"), "start", LocalDate.now(clock.withZone(zone)));
                    String endArg = ToolArguments.optionalString(args, "end");
                    int bound = 1_000_000;
                    int years = ToolArguments.optionalInt(args, "add_years", 0, -10_000, 10_000);
                    int months = ToolArguments.optionalInt(args, "add_months", 0, -bound, bound);
                    int weeks = ToolArguments.optionalInt(args, "add_weeks", 0, -bound, bound);
                    int days = ToolArguments.optionalInt(args, "add_days", 0, -bound, bound);
                    int businessDays = ToolArguments.optionalInt(args, "add_business_days", 0, -bound, bound);
                    boolean adding = years != 0 || months != 0 || weeks != 0 || days != 0 || businessDays != 0;

                    if (endArg != null && adding) {
                        throw new ToolInputException("Give either 'end' (to count days) or amounts to add, not both");
                    }
                    if (endArg != null) {
                        LocalDate end = date(endArg, "end", null);
                        return "From " + describe(start) + " to " + describe(end) + ": "
                                + ChronoUnit.DAYS.between(start, end) + " calendar days, "
                                + businessDaysBetween(start, end) + " business days.";
                    }
                    if (!adding) {
                        return "Today is " + describe(start) + ".";
                    }
                    LocalDate result = addBusinessDays(start.plusYears(years).plusMonths(months).plusWeeks(weeks).plusDays(days),
                            businessDays);
                    return describe(start) + " -> " + describe(result) + ".";
                });
    }

    public static RegisteredTool calculate() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CALCULATE)
                        .description("Evaluate an arithmetic expression exactly, in decimal — use it for any sum, "
                                + "percentage, or financial figure instead of doing arithmetic yourself. Supports "
                                + "+ - * / % (remainder) ^ (power), parentheses, pi, e, and abs, round(x, places), "
                                + "floor, ceil, sqrt, pow, ln, log10, exp, min, max, sum, avg. "
                                + "Example: round(1250 * 1.2 - 99.99, 2)")
                        .parameters(ToolSchemas.object(List.of("expression"),
                                "expression", ToolSchemas.string("The expression. Numbers without thousands separators or currency symbols.")))
                        .build(),
                (args, context) -> {
                    BigDecimal result = Calculator.evaluate(ToolArguments.requireString(args, "expression"));
                    return Calculator.format(result);
                });
    }

    static long businessDaysBetween(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return -businessDaysBetween(end, start);
        }
        long count = 0;
        // Whole weeks contribute five each; walk only the remainder.
        long days = ChronoUnit.DAYS.between(start, end);
        count += (days / 7) * 5;
        LocalDate cursor = start.plusDays((days / 7) * 7);
        while (cursor.isBefore(end)) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) {
                count++;
            }
        }
        return count;
    }

    static LocalDate addBusinessDays(LocalDate date, int businessDays) {
        int step = businessDays >= 0 ? 1 : -1;
        int remaining = Math.abs(businessDays);
        LocalDate cursor = date;
        while (remaining > 0) {
            cursor = cursor.plusDays(step);
            if (isBusinessDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }

    private static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static String describe(LocalDate date) {
        return date + " (" + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ")";
    }

    private static LocalDate date(String value, String name, LocalDate defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue == null) {
                throw new ToolInputException("'" + name + "' is required");
            }
            return defaultValue;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ToolInputException("'" + name + "' must be a date in YYYY-MM-DD form, was \"" + value + "\"");
        }
    }

    private static ZoneId zone(String id) {
        if (id == null || id.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(id.strip());
        } catch (DateTimeException e) {
            throw new ToolInputException("Unknown timezone \"" + id + "\"; use an IANA name like \"Europe/London\"");
        }
    }
}
