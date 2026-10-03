package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpreadsheetToolsTest {

    private ToolContext context;

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void createsAWorkbookWithFormulasFormatsHeaderAndDates() throws IOException {
        String result = create(Map.of("path", "finance/forecast", "sheets", List.of(Map.of(
                "name", "Forecast",
                "rows", List.of(
                        List.of("Month", "Revenue", "Costs", "Profit", "Starts"),
                        List.of("Jan", 1200.5, "800", "=B2-C2", "2026-01-01"),
                        List.of("Feb", "1500", 900, "=B3-C3", "2026-02-01"),
                        row("Total", "=SUM(B2:B3)", "=SUM(C2:C3)", "=SUM(D2:D3)", null),
                        List.of("Code", "'00123")),
                "number_formats", List.of(Map.of("columns", "B:D", "format", "£#,##0.00")),
                "column_widths", List.of(12)))));

        assertThat(result).startsWith("Created finance/forecast.xlsx").contains("'Forecast' 5 rows", "5 formula(s) evaluated, no errors");
        assertThat(context.workspace().require("finance/forecast.xlsx").mediaType()).isEqualTo(MediaTypes.XLSX);
        assertThat(context.workspace().changedPaths()).contains("finance/forecast.xlsx");

        try (Workbook workbook = load("finance/forecast.xlsx")) {
            Sheet sheet = workbook.getSheet("Forecast");
            Cell total = sheet.getRow(3).getCell(3);
            assertThat(total.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(total.getCellFormula()).isEqualTo("SUM(D2:D3)");
            assertThat(workbook.getCreationHelper().createFormulaEvaluator().evaluate(total).getNumberValue()).isEqualTo(1000.5);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(800);   // "800" became a number
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("00123"); // ' forced text
            assertThat(DateUtil.isCellDateFormatted(sheet.getRow(1).getCell(4))).isTrue();
            assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("£#,##0.00");
            assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getColumnWidth(0)).isEqualTo(12 * 256);
            assertThat(workbook.getForceFormulaRecalculation()).isTrue();
        }
    }

    @Test
    void cellsThatEvaluateToErrorsAreReportedForTheModelToFix() {
        String result = create(Map.of("path", "bad.xlsx", "sheets", List.of(Map.of("name", "S", "header", false,
                "rows", List.of(List.of(1, 0, "=A1/B1"), List.of(2, "=A2*2"))))));

        assertThat(result).contains("2 formula(s) evaluated", "these cells evaluate to errors", "'S'!C1 #DIV/0! (=A1/B1)")
                .doesNotContain("B2");
    }

    @Test
    void formulasNamingSomethingThatDoesNotExistAreRejectedUpFront() {
        assertThatThrownBy(() -> create(Map.of("path", "x.xlsx", "sheets", List.of(Map.of("name", "S",
                "rows", List.of(List.of("=Z99+NOSUCHNAME")))))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("NOSUCHNAME");
    }

    @Test
    void unparseableFormulasAreAFixableError() {
        assertThatThrownBy(() -> create(Map.of("path", "x.xlsx", "sheets", List.of(Map.of("name", "S",
                "rows", List.of(List.of("=SUM(A1:")))))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("A1").hasMessageContaining("invalid formula");
    }

    @Test
    void readShowsAnAddressableGridOfCalculatedValuesOrFormulas() {
        create(Map.of("path", "t.xlsx", "sheets", List.of(
                Map.of("name", "Data", "rows", List.of(List.of("Item", "Qty"), List.of("A", 2), List.of("B", 3), List.of("Sum", "=SUM(B2:B3)"))),
                Map.of("name", "Notes", "rows", List.of(List.of("hello"))))));

        String values = read(Map.of("path", "t.xlsx"));
        assertThat(values).contains("Sheets: 'Data' (4 rows), 'Notes' (1 rows)", "row | A | B", "1 | Item | Qty", "4 | Sum | 5");

        assertThat(read(Map.of("path", "t.xlsx", "show_formulas", true))).contains("4 | Sum | =SUM(B2:B3)");
        assertThat(read(Map.of("path", "t.xlsx", "sheet", "notes"))).contains("Showing 'Notes'", "1 | hello");
        assertThat(read(Map.of("path", "t.xlsx", "range", "B2:B3"))).contains("row | B\n2 | 2\n3 | 3");
        assertThat(read(Map.of("path", "t.xlsx", "max_rows", 2))).contains("[Showing 2 of 4 rows");
    }

    @Test
    void updateSetsCellsAppendsRowsAndRecalculates() throws IOException {
        create(Map.of("path", "t.xlsx", "sheets", List.of(
                Map.of("name", "Data", "rows", List.of(List.of("Item", "Qty"), List.of("A", 2), List.of("B", 3))))));

        String result = update(Map.of("path", "t.xlsx", "sheet", "Data",
                "cells", List.of(Map.of("cell", "B2", "value", 10), Map.of("cell", "D1", "value", "=SUM(B:B)", "format", "0.0")),
                "append_rows", List.of(List.of("C", 5))));

        assertThat(result).contains("2 cell(s) set", "1 row(s) appended at row 4", "no errors");
        try (Workbook workbook = load("t.xlsx")) {
            Sheet sheet = workbook.getSheet("Data");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("C");
            assertThat(workbook.getCreationHelper().createFormulaEvaluator().evaluate(sheet.getRow(0).getCell(3)).getNumberValue())
                    .isEqualTo(18);
            assertThat(sheet.getRow(0).getCell(3).getCellStyle().getDataFormatString()).isEqualTo("0.0");
        }
    }

    @Test
    void updateCanAddASheet() throws IOException {
        create(Map.of("path", "t.xlsx", "sheets", List.of(Map.of("name", "One", "rows", List.of(List.of("x"))))));

        update(Map.of("path", "t.xlsx", "sheet", "Two", "append_rows", List.of(List.of("y"))));

        try (Workbook workbook = load("t.xlsx")) {
            assertThat(workbook.getSheet("Two").getRow(0).getCell(0).getStringCellValue()).isEqualTo("y");
        }
    }

    @Test
    void legacyXlsFilesCanBeReadAndAreSavedAsXlsxWhenUpdated() throws IOException {
        try (HSSFWorkbook legacy = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = legacy.createSheet("Old");
            sheet.createRow(0).createCell(0).setCellValue(41);
            sheet.getRow(0).createCell(1).setCellFormula("A1+1");
            legacy.write(out);
            context.workspace().write("legacy.xls", out.toByteArray(), null);
        }

        assertThat(read(Map.of("path", "legacy.xls"))).contains("1 | 41 | 42");
        assertThat(update(Map.of("path", "legacy.xls", "sheet", "Old", "cells", List.of(Map.of("cell", "A1", "value", 1)))))
                .contains("Updated legacy.xlsx");
        try (Workbook workbook = load("legacy.xlsx")) {
            assertThat(workbook.getSheet("Old").getRow(0).getCell(1).getCellFormula()).isEqualTo("A1+1");
        }
    }

    @Test
    void mistakesAreFixableErrors() {
        create(Map.of("path", "t.xlsx", "sheets", List.of(Map.of("name", "Data", "rows", List.of(List.of("x"))))));

        assertThatThrownBy(() -> read(Map.of("path", "t.xlsx", "sheet", "Nope")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("sheets are [Data]");
        assertThatThrownBy(() -> update(Map.of("path", "t.xlsx", "sheet", "Data", "cells", List.of(Map.of("cell", "3B", "value", 1)))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("B3");
        assertThatThrownBy(() -> read(Map.of("path", "missing.xlsx")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("No such file");
        context.workspace().writeText("notes.txt", "not a workbook");
        assertThatThrownBy(() -> read(Map.of("path", "notes.txt")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't a readable Excel workbook");
        assertThatThrownBy(() -> create(Map.of("path", "d.xlsx", "sheets", List.of(
                Map.of("name", "Same", "rows", List.of()), Map.of("name", "Same", "rows", List.of())))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Two sheets");
    }

    private String create(Map<String, Object> args) {
        return SpreadsheetTools.create().handler().handle(new HashMap<>(args), context);
    }

    private String read(Map<String, Object> args) {
        return SpreadsheetTools.read().handler().handle(new HashMap<>(args), context);
    }

    private String update(Map<String, Object> args) {
        return SpreadsheetTools.update().handler().handle(new HashMap<>(args), context);
    }

    private Workbook load(String path) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(context.workspace().require(path).content()));
    }

    /** List.of rejects nulls; spreadsheet rows legitimately contain empty cells. */
    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
