package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtilityToolsTest {

    /** Friday 2026-10-02, 23:30 UTC — already Saturday in Tokyo. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T23:30:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0.1 + 0.2                       | 0.3",
            "2 + 3 * 4                       | 14",
            "(2 + 3) * 4                     | 20",
            "2 ^ 3 ^ 2                       | 512",
            "-2 ^ 2                          | 4",
            "10 / 4                          | 2.5",
            "10 % 4                          | 2",
            "1 / 3 * 3                       | 1",
            "round(1250 * 1.2 - 99.99, 2)    | 1400.01",
            "round(2.5)                      | 3",
            "round(1234.5678, -2)            | 1200",
            "sum(1, 2, 3.5)                  | 6.5",
            "avg(2, 4, 9)                    | 5",
            "max(3, -1, 7) - min(3, -1, 7)   | 8",
            "sqrt(144)                       | 12",
            "2 ^ -2                          | 0.25",
            "1.5e3 + 1                       | 1501",
            "abs(-12.5) + floor(2.7) + ceil(2.1) | 17.5",
            "1000000 * 1000000               | 1000000000000",
    })
    void calculatesExactlyInDecimal(String expression, String expected) {
        assertThat(call(UtilityTools.calculate(), Map.of("expression", expression))).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 / 0", "2 +", "foo(1)", "(1 + 2", "sqrt(-1)", "1,000 + 1", "ln(0)"})
    void badExpressionsAreFixableErrors(String expression) {
        assertThatThrownBy(() -> call(UtilityTools.calculate(), Map.of("expression", expression)))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void currentDatetimeHonoursTheTimezone() {
        assertThat(call(UtilityTools.currentDateTime(CLOCK), Map.of()))
                .startsWith("2026-10-02T23:30:00Z").contains("Friday");
        assertThat(call(UtilityTools.currentDateTime(CLOCK), Map.of("timezone", "Asia/Tokyo")))
                .startsWith("2026-10-03T08:30:00+09:00").contains("Saturday", "Asia/Tokyo");
    }

    @Test
    void unknownTimezoneIsAFixableError() {
        assertThatThrownBy(() -> call(UtilityTools.currentDateTime(CLOCK), Map.of("timezone", "Mars/Olympus")))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void addsBusinessDaysSkippingWeekends() {
        // Friday + 1 business day = Monday; + 5 = next Friday.
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("start", "2026-10-02", "add_business_days", 1)))
                .endsWith("2026-10-05 (Monday).");
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("start", "2026-10-02", "add_business_days", 5)))
                .endsWith("2026-10-09 (Friday).");
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("start", "2026-10-05", "add_business_days", -1)))
                .endsWith("2026-10-02 (Friday).");
    }

    @Test
    void addMonthsClampsToTheMonthEnd() {
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("start", "2026-01-31", "add_months", 1)))
                .endsWith("2026-02-28 (Saturday).");
    }

    @Test
    void startDefaultsToTodayInTheGivenTimezone() {
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("add_days", 0))).isEqualTo("Today is 2026-10-02 (Friday).");
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("timezone", "Asia/Tokyo")))
                .isEqualTo("Today is 2026-10-03 (Saturday).");
    }

    @Test
    void countsCalendarAndBusinessDaysBetweenDates() {
        assertThat(call(UtilityTools.dateCalculate(CLOCK), Map.of("start", "2026-10-02", "end", "2026-10-16")))
                .contains("14 calendar days", "10 business days");
    }

    @Test
    void businessDaysBetweenMatchesDayByDayCounting() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int offset = -40; offset <= 40; offset++) {
            LocalDate end = start.plusDays(offset);
            long expected = 0;
            LocalDate from = offset >= 0 ? start : end;
            LocalDate to = offset >= 0 ? end : start;
            for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() <= 5) {
                    expected++;
                }
            }
            assertThat(UtilityTools.businessDaysBetween(start, end)).isEqualTo(offset >= 0 ? expected : -expected);
        }
    }

    @Test
    void endAndAmountsTogetherAreAmbiguous() {
        assertThatThrownBy(() -> call(UtilityTools.dateCalculate(CLOCK),
                Map.of("start", "2026-10-02", "end", "2026-10-09", "add_days", 1)))
                .isInstanceOf(ToolInputException.class);
    }

    private static String call(RegisteredTool tool, Map<String, Object> args) {
        return tool.handler().handle(args, null);
    }
}
