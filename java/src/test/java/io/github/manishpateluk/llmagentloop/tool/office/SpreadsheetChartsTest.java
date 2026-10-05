package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPlotArea;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpreadsheetChartsTest {

    private static final List<List<Object>> SALES = List.of(
            List.of("Month", "Revenue", "Costs"),
            List.of("Jan", 100, 60),
            List.of("Feb", 120, 70),
            List.of("Mar", 150, 80));

    private ToolContext context;

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void createAddsColumnLineAndPieCharts() throws IOException {
        String result = create(List.of(
                chart("column", "Revenue vs costs", "A2:A4", List.of(series("Revenue", "B2:B4"), series("Costs", "C2:C4")), "E2"),
                chart("line", "Revenue trend", "A2:A4", List.of(series("Revenue", "B2:B4")), "E20"),
                chart("pie", "Revenue share", "A2:A4", List.of(series("Revenue", "B2:B4")), "N2")));

        assertThat(result).contains("3 chart(s) added");
        try (XSSFWorkbook workbook = load()) {
            List<XSSFChart> charts = workbook.getSheet("Sales").getDrawingPatriarch().getCharts();
            assertThat(charts).hasSize(3);
            CTPlotArea column = charts.get(0).getCTChart().getPlotArea();
            assertThat(column.getBarChartArray()).hasSize(1);
            assertThat(column.getBarChartArray(0).getSerArray()).hasSize(2);
            assertThat(column.getBarChartArray(0).getBarDir().getVal().toString()).isEqualTo("col");
            assertThat(charts.get(1).getCTChart().getPlotArea().getLineChartArray()).hasSize(1);
            assertThat(charts.get(2).getCTChart().getPlotArea().getPieChartArray()).hasSize(1);
            assertThat(charts.get(0).getTitleText().getString()).isEqualTo("Revenue vs costs");
        }
    }

    @Test
    void chartsCanUseDataFromAnotherSheetAndNumericCategories() throws IOException {
        Map<String, Object> data = Map.of("name", "Data", "rows", List.of(List.of("Year", "Users"), List.of(2024, 10), List.of(2025, 40)));
        Map<String, Object> dashboard = Map.of("name", "Dashboard", "rows", List.of(List.of("Overview")),
                "charts", List.of(chart("bar", "Users", "Data!A2:A3", List.of(series("Users", "Data!B2:B3")), "A3")));

        SpreadsheetTools.create().handler().handle(new HashMap<>(Map.of("path", "kpi.xlsx", "sheets", List.of(data, dashboard))), context);

        try (XSSFWorkbook workbook = load("kpi.xlsx")) {
            XSSFChart chart = workbook.getSheet("Dashboard").getDrawingPatriarch().getCharts().getFirst();
            assertThat(chart.getCTChart().getPlotArea().getBarChartArray(0).getBarDir().getVal().toString()).isEqualTo("bar");
            assertThat(chart.getCTChart().getPlotArea().getBarChartArray(0).getSerArray(0).getVal().getNumRef().getF())
                    .contains("Data").contains("$B$2:$B$3");
        }
    }

    @Test
    void updateCanAddAChartToAnExistingWorkbook() throws IOException {
        create(List.of());

        String result = SpreadsheetTools.update().handler().handle(new HashMap<>(Map.of("path", "sales.xlsx", "sheet", "Sales",
                "charts", List.of(chart("line", "Costs", "A2:A4", List.of(series("Costs", "C2:C4")), "F2")))), context);

        assertThat(result).contains("1 chart(s) added");
        try (XSSFWorkbook workbook = load()) {
            assertThat(workbook.getSheet("Sales").getDrawingPatriarch().getCharts()).hasSize(1);
        }
    }

    @Test
    void chartMistakesAreFixableErrors() {
        assertThatThrownBy(() -> create(List.of(chart("radar", "x", "A2:A4", List.of(series("x", "B2:B4")), "E2"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("type must be one of");
        assertThatThrownBy(() -> create(List.of(chart("bar", "x", "nonsense", List.of(series("x", "B2:B4")), "E2"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't a cell range");
        assertThatThrownBy(() -> create(List.of(chart("bar", "x", "Missing!A2:A4", List.of(series("x", "B2:B4")), "E2"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("no sheet called 'Missing'");
        assertThatThrownBy(() -> create(List.of(chart("bar", "x", "A2:A4", List.of(), "E2"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("at least one series");
    }

    private String create(List<Map<String, Object>> charts) {
        Map<String, Object> sheet = new HashMap<>(Map.of("name", "Sales", "rows", SALES));
        sheet.put("charts", charts);
        return SpreadsheetTools.create().handler().handle(new HashMap<>(Map.of("path", "sales.xlsx", "sheets", List.of(sheet))), context);
    }

    private static Map<String, Object> chart(String type, String title, String categories, List<Map<String, Object>> series, String anchor) {
        return Map.of("type", type, "title", title, "categories", categories, "series", series, "anchor", anchor);
    }

    private static Map<String, Object> series(String name, String values) {
        return Map.of("name", name, "values", values);
    }

    private XSSFWorkbook load() throws IOException {
        return load("sales.xlsx");
    }

    private XSSFWorkbook load(String path) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(context.workspace().require(path).content()));
    }
}
