package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataToolsTest {

    private static final String SALES = """
            region,product,amount,notes
            North,Widgets,"£1,200.50",
            South,Widgets,800,"late, but paid"
            North,Gadgets,(300),refund
            South,Gadgets,450.25,
            North,Widgets,99.50,
            """;

    private ToolContext context;

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
        context.workspace().writeText("sales.csv", SALES);
    }

    @Test
    void justAPathShowsTheColumnsAndRows() {
        String result = query(Map.of("path", "sales.csv"));

        assertThat(result).startsWith("5 row(s)").contains("region,product,amount,notes", "\"late, but paid\"");
    }

    @Test
    void filtersNumericallyUnderstandingCurrencyThousandsAndParenthesisNegatives() {
        String result = query(Map.of("path", "sales.csv", "columns", List.of("product", "amount"),
                "where", List.of(Map.of("column", "amount", "op", ">", "value", "500"))));

        assertThat(result).startsWith("2 row(s)").contains("Widgets,\"£1,200.50\"", "Widgets,800")
                .doesNotContain("(300)");
    }

    @Test
    void groupsAndAggregatesExactly() {
        String result = query(Map.of("path", "sales.csv",
                "group_by", List.of("region"),
                "aggregates", List.of(
                        Map.of("function", "sum", "column", "amount", "as", "total"),
                        Map.of("function", "count")),
                "order_by", "total", "descending", true));

        assertThat(result).contains("region,total,count\nSouth,1250.25,2\nNorth,1000,3\n");
    }

    @Test
    void aggregatesWithoutGroupByGiveOneRow() {
        assertThat(query(Map.of("path", "sales.csv",
                "aggregates", List.of(Map.of("function", "avg", "column", "amount")))))
                .contains("avg_amount\n450.05\n");
    }

    @Test
    void textFiltersAreCaseInsensitive() {
        assertThat(query(Map.of("path", "sales.csv",
                "where", List.of(Map.of("column", "notes", "op", "contains", "value", "REFUND")))))
                .startsWith("1 row(s)");
        assertThat(query(Map.of("path", "sales.csv",
                "where", List.of(Map.of("column", "notes", "op", "is_empty")))))
                .startsWith("3 row(s)");
    }

    @Test
    void saveAsWritesTheFullResultAndLimitOnlyTrimsWhatIsShown() {
        String result = query(Map.of("path", "sales.csv", "limit", 2, "save_as", "out/north.csv",
                "where", List.of(Map.of("column", "region", "op", "=", "value", "north"))));

        assertThat(result).startsWith("3 row(s), showing the first 2").contains("Saved all 3 rows to out/north.csv");
        assertThat(context.workspace().require("out/north.csv").text().lines()).hasSize(4);
        assertThat(context.workspace().changedPaths()).contains("out/north.csv");
    }

    @Test
    void readsJsonArraysOfObjects() {
        context.workspace().writeText("people.json", """
                [{"name": "Ann", "age": 31}, {"name": "Bo", "age": 25, "team": "ops"}]
                """);

        assertThat(query(Map.of("path", "people.json", "order_by", "age")))
                .contains("name,age,team\nBo,25,ops\nAnn,31,\n");
    }

    @Test
    void semicolonDelimitedFilesAreDetected() {
        context.workspace().writeText("eu.csv", "name;amount\nA;1\nB;2\n");

        assertThat(query(Map.of("path", "eu.csv", "aggregates", List.of(Map.of("function", "sum", "column", "amount")))))
                .contains("sum_amount\n3\n");
    }

    @Test
    void unknownColumnsAndMissingFilesAreFixableErrors() {
        assertThatThrownBy(() -> query(Map.of("path", "sales.csv", "group_by", List.of("country"))))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("columns are [region, product, amount, notes]");
        assertThatThrownBy(() -> query(Map.of("path", "missing.csv")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("No such file");
    }

    @Test
    void lenientNumberParsing() {
        assertThat(DataTools.number("£1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(DataTools.number("(300)")).isEqualByComparingTo("-300");
        assertThat(DataTools.number("12%")).isEqualByComparingTo("12");
        assertThat(DataTools.number("abc")).isNull();
        assertThat(DataTools.number("")).isNull();
    }

    private String query(Map<String, Object> args) {
        return DataTools.query().handler().handle(args, context);
    }
}
