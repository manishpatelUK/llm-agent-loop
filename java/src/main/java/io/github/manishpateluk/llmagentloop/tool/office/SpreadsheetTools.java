package io.github.manishpateluk.llmagentloop.tool.office;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in Excel tools, on Apache POI: {@code spreadsheet_create} (a whole workbook from one
 * declarative spec — sheets, rows, formulas, number formats, widths, frozen headers),
 * {@code spreadsheet_read} (any .xlsx/.xls in the workspace, as an addressable grid) and
 * {@code spreadsheet_update} (set cells, append rows, add sheets). Files live in the workspace, so
 * a created workbook appears in {@code AgentLoopResult.changedFiles()} ready to hand to the user.
 *
 * <p>Every formula is evaluated before saving: a formula POI can't parse is reported back to the
 * model as a fixable error, and cells that evaluate to an Excel error ({@code #DIV/0!},
 * {@code #REF!}...) are listed in the result so the model can correct them. Saved workbooks are
 * also flagged to recalculate when opened. Register with
 * {@code registry.registerAll(SpreadsheetTools.all())}; needs a workspace configured.
 */
public final class SpreadsheetTools {

    public static final String CREATE = "spreadsheet_create";
    public static final String READ = "spreadsheet_read";
    public static final String UPDATE = "spreadsheet_update";

    static final int MAX_CELLS_PER_CALL = 200_000;
    static final int DEFAULT_READ_ROWS = 100;
    static final int MAX_READ_ROWS = 2_000;
    private static final int MAX_REPORTED_ERRORS = 20;
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String CELL_CONVENTIONS = "Cell values: numbers as numbers (no currency symbols or "
            + "thousands separators — apply a number format instead); text starting with '=' is a formula, e.g. "
            + "\"=SUM(B2:B10)\"; dates as \"YYYY-MM-DD\"; true/false; null for empty; prefix with ' to force text, "
            + "e.g. \"'00123\".";

    private SpreadsheetTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(create(), read(), update());
    }

    // ---- spreadsheet_create ------------------------------------------------------------------

    public static RegisteredTool create() {
        Map<String, Object> numberFormat = ToolSchemas.object(List.of("columns", "format"),
                "columns", ToolSchemas.string("Column letter or range of letters, e.g. \"B\" or \"B:D\"."),
                "format", ToolSchemas.string("Excel number format, e.g. \"#,##0.00\", \"£#,##0\", \"0.0%\", \"yyyy-mm-dd\"."));
        Map<String, Object> sheet = ToolSchemas.object(List.of("name", "rows"),
                "name", ToolSchemas.string("Sheet name (max 31 characters)."),
                "rows", ToolSchemas.array("Rows, top to bottom; each row is a list of cell values, left to right.",
                        ToolSchemas.stringArray("One row's cell values.")),
                "header", ToolSchemas.bool("Style the first row as a bold, shaded header and freeze it. Defaults to true."),
                "column_widths", ToolSchemas.array("Column widths in characters, left to right.",
                        Map.of("type", "integer")),
                "number_formats", ToolSchemas.array("Number formats for columns (data rows only).", numberFormat));
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CREATE)
                        .description("Create an Excel workbook (.xlsx) in the workspace, or replace an existing one, "
                                + "with one or more sheets. Use formulas for anything calculated (totals, growth, "
                                + "ratios) so the spreadsheet stays live. " + CELL_CONVENTIONS)
                        .parameters(ToolSchemas.object(List.of("path", "sheets"),
                                "path", ToolSchemas.string("Workspace path ending in .xlsx, e.g. \"finance/forecast.xlsx\"."),
                                "sheets", ToolSchemas.array("The sheets, in tab order.", sheet)))
                        .build(),
                SpreadsheetTools::create);
    }

    private static String create(Map<String, Object> args, ToolContext context) {
        String path = xlsxPath(ToolArguments.requireString(args, "path"));
        List<Map<String, Object>> sheets = maps(args.get("sheets"), "sheets");
        if (sheets.isEmpty()) {
            throw new ToolInputException("At least one sheet is required");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            int cells = 0;
            for (Map<String, Object> spec : sheets) {
                String name = sheetName(ToolArguments.requireString(spec, "name"));
                if (workbook.getSheet(name) != null) {
                    throw new ToolInputException("Two sheets are both called '" + name + "'");
                }
                Sheet sheet = workbook.createSheet(name);
                List<List<Object>> rows = rows(spec.get("rows"), "rows of sheet '" + name + "'");
                cells += rows.stream().mapToInt(List::size).sum();
                if (cells > MAX_CELLS_PER_CALL) {
                    throw new ToolInputException("Too many cells in one call (limit " + MAX_CELLS_PER_CALL + "); split the work into several calls");
                }
                writeRows(sheet, 0, rows, styles);
                boolean header = ToolArguments.optionalBoolean(spec, "header", true);
                if (header && !rows.isEmpty()) {
                    Row first = sheet.getRow(0);
                    if (first != null) {
                        for (Cell cell : first) {
                            cell.setCellStyle(styles.header);
                        }
                    }
                    sheet.createFreezePane(0, 1);
                }
                applyNumberFormats(sheet, maps(spec.get("number_formats"), "number_formats"), header ? 1 : 0, styles);
                applyWidths(sheet, spec.get("column_widths"), rows);
            }
            Evaluation evaluation = evaluate(workbook);
            WorkspaceFile saved = save(context, path, workbook);
            StringBuilder out = new StringBuilder("Created ").append(saved.path()).append(" (").append(saved.size()).append(" bytes): ");
            List<String> summaries = new ArrayList<>();
            for (Sheet sheet : workbook) {
                summaries.add("'" + sheet.getSheetName() + "' " + (sheet.getLastRowNum() + 1) + " rows");
            }
            out.append(String.join(", ", summaries)).append('.');
            return out.append(evaluation.describe()).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- spreadsheet_read --------------------------------------------------------------------

    public static RegisteredTool read() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(READ)
                        .description("Read an Excel workbook (.xlsx or .xls) from the workspace. Lists its sheets and "
                                + "shows one sheet as a grid with row numbers and column letters, so cells can be "
                                + "addressed (e.g. B3) in spreadsheet_update. Shows calculated values; set "
                                + "show_formulas to see the formulas instead.")
                        .parameters(ToolSchemas.object(List.of("path"),
                                "path", ToolSchemas.string("Workspace path of the workbook."),
                                "sheet", ToolSchemas.string("Sheet name. Defaults to the first sheet."),
                                "range", ToolSchemas.string("Optional cell range to show, e.g. \"A1:F40\"."),
                                "max_rows", ToolSchemas.integer("Maximum rows to show, up to " + MAX_READ_ROWS + ". Defaults to " + DEFAULT_READ_ROWS + "."),
                                "show_formulas", ToolSchemas.bool("Show formulas rather than their values. Defaults to false.")))
                        .build(),
                SpreadsheetTools::read);
    }

    private static String read(Map<String, Object> args, ToolContext context) {
        String path = ToolArguments.requireString(args, "path");
        try (Workbook workbook = open(context, path)) {
            Sheet sheet = sheet(workbook, ToolArguments.optionalString(args, "sheet"), false);
            int maxRows = ToolArguments.optionalInt(args, "max_rows", DEFAULT_READ_ROWS, 1, MAX_READ_ROWS);
            boolean showFormulas = ToolArguments.optionalBoolean(args, "show_formulas", false);

            int firstRow = 0;
            int lastRow = sheet.getLastRowNum();
            int firstCol = 0;
            int lastCol = 0;
            for (Row row : sheet) {
                lastCol = Math.max(lastCol, row.getLastCellNum() - 1);
            }
            String range = ToolArguments.optionalString(args, "range");
            if (range != null && !range.isBlank()) {
                CellRangeAddress area;
                try {
                    area = CellRangeAddress.valueOf(range.strip().toUpperCase(Locale.ROOT));
                } catch (RuntimeException e) {
                    throw new ToolInputException("'range' must look like \"A1:F40\"");
                }
                firstRow = area.getFirstRow();
                lastRow = area.getLastRow();
                firstCol = area.getFirstColumn();
                lastCol = area.getLastColumn();
            }

            StringBuilder out = new StringBuilder("Sheets: ");
            List<String> names = new ArrayList<>();
            for (Sheet s : workbook) {
                names.add("'" + s.getSheetName() + "' (" + (s.getPhysicalNumberOfRows() == 0 ? 0 : s.getLastRowNum() + 1) + " rows)");
            }
            out.append(String.join(", ", names)).append("\nShowing '").append(sheet.getSheetName()).append("'");
            if (sheet.getPhysicalNumberOfRows() == 0) {
                return out.append(": empty.").toString();
            }
            out.append(":\n");

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.UK);
            out.append("row");
            for (int c = firstCol; c <= lastCol; c++) {
                out.append(" | ").append(CellReference.convertNumToColString(c));
            }
            out.append('\n');
            int shown = 0;
            for (int r = firstRow; r <= lastRow && shown < maxRows; r++, shown++) {
                Row row = sheet.getRow(r);
                out.append(r + 1);
                for (int c = firstCol; c <= lastCol; c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    out.append(" | ").append(display(cell, showFormulas, evaluator, formatter).replace("\n", " "));
                }
                out.append('\n');
            }
            if (lastRow - firstRow + 1 > shown) {
                out.append("[Showing ").append(shown).append(" of ").append(lastRow - firstRow + 1)
                        .append(" rows; use 'range' to see others.]");
            }
            return out.toString().strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- spreadsheet_update ------------------------------------------------------------------

    public static RegisteredTool update() {
        Map<String, Object> cellUpdate = ToolSchemas.object(List.of("cell", "value"),
                "cell", ToolSchemas.string("Cell address, e.g. \"B3\"."),
                "value", ToolSchemas.string("New value (same conventions as the rows). null clears the cell."),
                "format", ToolSchemas.string("Optional number format for this cell, e.g. \"0.0%\"."));
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(UPDATE)
                        .description("Change an existing Excel workbook in the workspace: set individual cells and/or "
                                + "append rows to the end of a sheet (creating the sheet if it doesn't exist). Read it "
                                + "first with spreadsheet_read to find the right cells. " + CELL_CONVENTIONS)
                        .parameters(ToolSchemas.object(List.of("path", "sheet"),
                                "path", ToolSchemas.string("Workspace path of the workbook."),
                                "sheet", ToolSchemas.string("Sheet name."),
                                "cells", ToolSchemas.array("Cells to set.", cellUpdate),
                                "append_rows", ToolSchemas.array("Rows to add after the last row.",
                                        ToolSchemas.stringArray("One row's cell values."))))
                        .build(),
                SpreadsheetTools::update);
    }

    private static String update(Map<String, Object> args, ToolContext context) {
        String path = ToolArguments.requireString(args, "path");
        List<Map<String, Object>> updates = maps(args.get("cells"), "cells");
        List<List<Object>> appended = rows(args.get("append_rows"), "append_rows");
        if (updates.isEmpty() && appended.isEmpty()) {
            throw new ToolInputException("Nothing to do: give 'cells' and/or 'append_rows'");
        }
        if (updates.size() + appended.stream().mapToInt(List::size).sum() > MAX_CELLS_PER_CALL) {
            throw new ToolInputException("Too many cells in one call (limit " + MAX_CELLS_PER_CALL + ")");
        }
        try (Workbook original = open(context, path); Workbook workbook = asXlsx(original)) {
            Styles styles = new Styles(workbook);
            Sheet sheet = sheet(workbook, ToolArguments.requireString(args, "sheet"), true);
            for (Map<String, Object> update : updates) {
                CellReference ref = reference(ToolArguments.requireString(update, "cell"));
                Row row = sheet.getRow(ref.getRow()) == null ? sheet.createRow(ref.getRow()) : sheet.getRow(ref.getRow());
                Cell cell = row.getCell(ref.getCol()) == null ? row.createCell(ref.getCol()) : row.getCell(ref.getCol());
                setValue(cell, update.get("value"), styles);
                String format = ToolArguments.optionalString(update, "format");
                if (format != null && !format.isBlank()) {
                    cell.setCellStyle(styles.withFormat(cell.getCellStyle(), format));
                }
            }
            int start = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
            writeRows(sheet, start, appended, styles);

            Evaluation evaluation = evaluate(workbook);
            String savedPath = path.toLowerCase(Locale.ROOT).endsWith(".xls") ? path + "x" : path;
            WorkspaceFile saved = save(context, savedPath, workbook);
            StringBuilder out = new StringBuilder("Updated ").append(saved.path()).append(": ");
            List<String> done = new ArrayList<>();
            if (!updates.isEmpty()) {
                done.add(updates.size() + " cell(s) set");
            }
            if (!appended.isEmpty()) {
                done.add(appended.size() + " row(s) appended at row " + (start + 1));
            }
            out.append(String.join(", ", done)).append(" on '").append(sheet.getSheetName()).append("'.");
            if (!saved.path().equals(xlsxPathOrSame(path))) {
                out.append(" (Saved as .xlsx.)");
            }
            return out.append(evaluation.describe()).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- shared ------------------------------------------------------------------------------

    private static final class Styles {
        final Workbook workbook;
        final CellStyle header;
        final CellStyle date;
        final Map<String, CellStyle> derived = new HashMap<>();

        Styles(Workbook workbook) {
            this.workbook = workbook;
            CreationHelper helper = workbook.getCreationHelper();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            date = workbook.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
        }

        /** {@code base} with its number format replaced, cached so a column of cells shares one style. */
        CellStyle withFormat(CellStyle base, String format) {
            return derived.computeIfAbsent(base.getIndex() + "|" + format, key -> {
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(base);
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(format));
                return style;
            });
        }
    }

    private static void writeRows(Sheet sheet, int startRow, List<List<Object>> rows, Styles styles) {
        int maxRows = SpreadsheetVersion.EXCEL2007.getMaxRows();
        int maxCols = SpreadsheetVersion.EXCEL2007.getMaxColumns();
        if (startRow + rows.size() > maxRows) {
            throw new ToolInputException("That would exceed Excel's " + maxRows + "-row limit");
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Object> values = rows.get(r);
            if (values.size() > maxCols) {
                throw new ToolInputException("Row " + (startRow + r + 1) + " has more than Excel's " + maxCols + " columns");
            }
            Row row = sheet.createRow(startRow + r);
            for (int c = 0; c < values.size(); c++) {
                Object value = values.get(c);
                if (value == null) {
                    continue;
                }
                setValue(row.createCell(c), value, styles);
            }
        }
    }

    /** Applies the cell-value conventions in {@link #CELL_CONVENTIONS}. */
    private static void setValue(Cell cell, Object value, Styles styles) {
        String ref = new CellReference(cell).formatAsString(false);
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return;
        }
        if (value instanceof Map || value instanceof List) {
            throw new ToolInputException("Cell " + ref + " must be a single value, not a list or object");
        }
        String text = String.valueOf(value);
        if (text.startsWith("'")) {
            cell.setCellValue(text.substring(1));
        } else if (text.startsWith("=") && text.length() > 1) {
            try {
                cell.setCellFormula(text.substring(1));
            } catch (RuntimeException e) {
                throw new ToolInputException("Cell " + ref + ": invalid formula " + text + " (" + e.getMessage() + ")");
            }
        } else if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            cell.setCellValue(Boolean.parseBoolean(text));
        } else if (PLAIN_NUMBER.matcher(text).matches()) {
            cell.setCellValue(new BigDecimal(text).doubleValue());
        } else if (ISO_DATE.matcher(text).matches()) {
            try {
                cell.setCellValue(LocalDate.parse(text));
                cell.setCellStyle(styles.date);
            } catch (DateTimeParseException e) {
                cell.setCellValue(text);
            }
        } else {
            cell.setCellValue(text);
        }
    }

    private static void applyNumberFormats(Sheet sheet, List<Map<String, Object>> formats, int firstDataRow, Styles styles) {
        for (Map<String, Object> spec : formats) {
            String columns = ToolArguments.requireString(spec, "columns").strip().toUpperCase(Locale.ROOT);
            String format = ToolArguments.requireString(spec, "format");
            String[] ends = columns.split(":");
            int from;
            int to;
            try {
                from = CellReference.convertColStringToIndex(ends[0].strip());
                to = ends.length > 1 ? CellReference.convertColStringToIndex(ends[1].strip()) : from;
            } catch (RuntimeException e) {
                throw new ToolInputException("'columns' must be a column letter or range like \"B:D\", was \"" + columns + "\"");
            }
            if (from < 0 || to < from || ends.length > 2) {
                throw new ToolInputException("'columns' must be a column letter or range like \"B:D\", was \"" + columns + "\"");
            }
            for (int r = firstDataRow; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = from; c <= to; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null) {
                        cell.setCellStyle(styles.withFormat(cell.getCellStyle(), format));
                    }
                }
            }
        }
    }

    /** Explicit widths where given; otherwise a width from the longest value, without needing fonts (AWT). */
    private static void applyWidths(Sheet sheet, Object widths, List<List<Object>> rows) {
        List<Object> given = widths instanceof List<?> list ? new ArrayList<>(list) : List.of();
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        for (int c = 0; c < columns; c++) {
            int chars;
            if (c < given.size() && given.get(c) instanceof Number n) {
                chars = n.intValue();
            } else {
                int col = c;
                chars = rows.stream()
                        .filter(row -> col < row.size() && row.get(col) != null)
                        .mapToInt(row -> {
                            String text = String.valueOf(row.get(col));
                            return text.startsWith("=") ? 12 : text.length();
                        })
                        .max().orElse(8) + 2;
            }
            sheet.setColumnWidth(c, Math.max(4, Math.min(chars, 80)) * 256);
        }
    }

    private record Evaluation(List<String> errors, int formulas) {
        String describe() {
            if (formulas == 0) {
                return "";
            }
            StringBuilder out = new StringBuilder(" ").append(formulas).append(" formula(s) evaluated");
            if (errors.isEmpty()) {
                return out.append(", no errors.").toString();
            }
            out.append("; these cells evaluate to errors and should be fixed: ")
                    .append(String.join(", ", errors.subList(0, Math.min(errors.size(), MAX_REPORTED_ERRORS))));
            if (errors.size() > MAX_REPORTED_ERRORS) {
                out.append(" and ").append(errors.size() - MAX_REPORTED_ERRORS).append(" more");
            }
            return out.append('.').toString();
        }
    }

    /** Evaluates every formula, so a broken one is caught now rather than by the user opening the file. */
    private static Evaluation evaluate(Workbook workbook) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<String> errors = new ArrayList<>();
        int formulas = 0;
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) {
                        continue;
                    }
                    formulas++;
                    String ref = "'" + sheet.getSheetName() + "'!" + new CellReference(cell).formatAsString(false);
                    try {
                        CellType result = evaluator.evaluateFormulaCell(cell);
                        if (result == CellType.ERROR) {
                            errors.add(ref + " " + FormulaError.forInt(cell.getErrorCellValue()).getString()
                                    + " (=" + cell.getCellFormula() + ")");
                        }
                    } catch (RuntimeException e) {
                        // Usually a function POI can't evaluate; Excel will still calculate it on open.
                        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        if (reason.contains("not implemented") || reason.contains("NotImplemented")) {
                            continue;
                        }
                        errors.add(ref + " (=" + cell.getCellFormula() + "): " + reason);
                    }
                }
            }
        }
        workbook.setForceFormulaRecalculation(true);
        return new Evaluation(errors, formulas);
    }

    private static String display(Cell cell, boolean showFormulas, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            if (showFormulas) {
                return "=" + cell.getCellFormula();
            }
            try {
                return formatter.formatCellValue(cell, evaluator);
            } catch (RuntimeException e) {
                return "=" + cell.getCellFormula();
            }
        }
        return formatter.formatCellValue(cell);
    }

    private static Workbook open(ToolContext context, String path) {
        WorkspaceFile file;
        try {
            file = context.workspace().require(path);
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        }
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(file.content()));
        } catch (IOException | RuntimeException e) {
            throw new ToolInputException(file.path() + " isn't a readable Excel workbook (.xlsx or .xls)");
        }
    }

    /** Updates are always saved as .xlsx; an .xls is converted by copying its values and formulas. */
    private static Workbook asXlsx(Workbook workbook) {
        if (workbook instanceof XSSFWorkbook) {
            return workbook;
        }
        XSSFWorkbook converted = new XSSFWorkbook();
        for (Sheet source : workbook) {
            Sheet target = converted.createSheet(source.getSheetName());
            for (Row sourceRow : source) {
                Row row = target.createRow(sourceRow.getRowNum());
                for (Cell sourceCell : sourceRow) {
                    Cell cell = row.createCell(sourceCell.getColumnIndex());
                    switch (sourceCell.getCellType()) {
                        case FORMULA -> cell.setCellFormula(sourceCell.getCellFormula());
                        case NUMERIC -> cell.setCellValue(sourceCell.getNumericCellValue());
                        case BOOLEAN -> cell.setCellValue(sourceCell.getBooleanCellValue());
                        case STRING -> cell.setCellValue(sourceCell.getStringCellValue());
                        default -> {
                        }
                    }
                }
            }
        }
        return converted;
    }

    private static WorkspaceFile save(ToolContext context, String path, Workbook workbook) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        workbook.write(bytes);
        try {
            return context.workspace().write(path, bytes.toByteArray(), MediaTypes.XLSX);
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        }
    }

    private static Sheet sheet(Workbook workbook, String name, boolean createIfMissing) {
        if (name == null || name.isBlank()) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ToolInputException("The workbook has no sheets");
            }
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            for (Sheet candidate : workbook) {
                if (candidate.getSheetName().equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
            if (createIfMissing) {
                return workbook.createSheet(sheetName(name));
            }
            List<String> names = new ArrayList<>();
            workbook.forEach(s -> names.add(s.getSheetName()));
            throw new ToolInputException("No sheet called '" + name + "'; sheets are " + names);
        }
        return sheet;
    }

    private static String sheetName(String name) {
        String safe = WorkbookUtil.createSafeSheetName(name.strip());
        if (safe.isBlank()) {
            throw new ToolInputException("Sheet name must not be empty");
        }
        return safe;
    }

    private static CellReference reference(String address) {
        String a = address.strip().toUpperCase(Locale.ROOT);
        if (!a.matches("[A-Z]{1,3}[1-9]\\d{0,6}")) {
            throw new ToolInputException("Cell address must look like \"B3\", was \"" + address + "\"");
        }
        return new CellReference(a);
    }

    private static String xlsxPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xls")) {
            return path + "x";
        }
        return lower.endsWith(".xlsx") ? path : path + ".xlsx";
    }

    private static String xlsxPathOrSame(String path) {
        try {
            return io.github.manishpateluk.llmagentloop.workspace.ScopedWorkspace.normalize(path);
        } catch (WorkspaceException e) {
            return path;
        }
    }

    private static List<Map<String, Object>> maps(Object value, String name) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(Map.class::isInstance)) {
            throw new ToolInputException("'" + name + "' must be a list of objects");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
        return typed;
    }

    private static List<List<Object>> rows(Object value, String name) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(row -> row == null || row instanceof List)) {
            throw new ToolInputException("'" + name + "' must be a list of rows, each a list of cell values");
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object row : list) {
            rows.add(row == null ? List.of() : new ArrayList<>((List<?>) row));
        }
        return rows;
    }
}
