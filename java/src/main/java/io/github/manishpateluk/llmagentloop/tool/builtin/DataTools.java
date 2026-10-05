package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code data_query}: filter, group, aggregate and sort a CSV or JSON table in the workspace —
 * the bread and butter of finance and admin work, done exactly rather than by the model eyeballing
 * rows. Register with {@code registry.registerAll(DataTools.all())}; needs a workspace configured.
 */
public final class DataTools {

    public static final String QUERY = "data_query";

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 1_000;
    private static final List<String> OPERATORS =
            List.of("=", "!=", ">", ">=", "<", "<=", "contains", "starts_with", "is_empty", "not_empty");
    private static final List<String> FUNCTIONS = List.of("count", "sum", "avg", "min", "max");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DataTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(query());
    }

    public static RegisteredTool query() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(QUERY)
                        .description("Query a CSV or JSON (array of objects) file in the workspace: filter rows, "
                                + "group them, compute count/sum/avg/min/max, sort, and optionally save the result as "
                                + "a new CSV. Numbers like \"£1,200.50\" or \"(300)\" are understood. Call with just "
                                + "a path to see the columns and first rows.")
                        .parameters(ToolSchemas.object(List.of("path"),
                                "path", ToolSchemas.string("Workspace path of the .csv or .json file."),
                                "columns", ToolSchemas.stringArray("Columns to return when not aggregating. Defaults to all."),
                                "where", ToolSchemas.array("Row filters, all of which must match.", ToolSchemas.object(
                                        List.of("column", "op"),
                                        "column", ToolSchemas.string("Column name."),
                                        "op", ToolSchemas.stringEnum("Comparison.", OPERATORS),
                                        "value", ToolSchemas.string("Value to compare with (not needed for is_empty/not_empty)."))),
                                "group_by", ToolSchemas.stringArray("Columns to group by."),
                                "aggregates", ToolSchemas.array("Values to compute per group (or over all rows).", ToolSchemas.object(
                                        List.of("function"),
                                        "function", ToolSchemas.stringEnum("Aggregate function.", FUNCTIONS),
                                        "column", ToolSchemas.string("Column to aggregate; omit for count of rows."),
                                        "as", ToolSchemas.string("Name for the result column."))),
                                "order_by", ToolSchemas.string("Result column to sort by."),
                                "descending", ToolSchemas.bool("Sort largest first. Defaults to false."),
                                "limit", ToolSchemas.integer("Maximum rows to return, up to " + MAX_LIMIT + ". Defaults to " + DEFAULT_LIMIT + "."),
                                "save_as", ToolSchemas.string("Optional workspace path to save the full result to as CSV.")))
                        .build(),
                DataTools::run);
    }

    private static String run(Map<String, Object> args, ToolContext context) {
        String path = ToolArguments.requireString(args, "path");
        Csv.Table table;
        try {
            table = load(context.workspace().require(path));
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        }

        List<List<String>> rows = filter(table, maps(args.get("where"), "where"));
        List<String> groupBy = ToolArguments.optionalStringList(args, "group_by");
        List<Map<String, Object>> aggregates = maps(args.get("aggregates"), "aggregates");
        groupBy.forEach(column -> index(table, column));

        List<String> outColumns;
        List<List<String>> outRows;
        if (!aggregates.isEmpty() || !groupBy.isEmpty()) {
            if (aggregates.isEmpty()) {
                aggregates = List.of(Map.of("function", "count"));
            }
            outColumns = new ArrayList<>(groupBy);
            for (Map<String, Object> aggregate : aggregates) {
                outColumns.add(aggregateName(aggregate));
            }
            outRows = aggregate(table, rows, groupBy, aggregates);
        } else {
            List<String> selected = ToolArguments.optionalStringList(args, "columns");
            outColumns = selected.isEmpty() ? table.columns() : selected;
            List<Integer> indexes = outColumns.stream().map(column -> index(table, column)).toList();
            outRows = rows.stream().map(row -> indexes.stream().map(row::get).toList()).toList();
        }

        String orderBy = ToolArguments.optionalString(args, "order_by");
        if (orderBy != null && !orderBy.isBlank()) {
            int at = outColumns.indexOf(orderBy);
            if (at < 0) {
                throw new ToolInputException("order_by column '" + orderBy + "' isn't in the result; result columns are " + outColumns);
            }
            Comparator<List<String>> comparator = (a, b) -> compareValues(a.get(at), b.get(at));
            if (ToolArguments.optionalBoolean(args, "descending", false)) {
                comparator = comparator.reversed();
            }
            outRows = outRows.stream().sorted(comparator).toList();
        }

        String saveAs = ToolArguments.optionalString(args, "save_as");
        String saved = "";
        if (saveAs != null && !saveAs.isBlank()) {
            try {
                saved = " Saved all " + outRows.size() + " rows to "
                        + context.workspace().writeText(saveAs, Csv.write(outColumns, outRows)).path() + ".";
            } catch (WorkspaceException e) {
                throw new ToolInputException(e.getMessage());
            }
        }

        int limit = ToolArguments.optionalInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<List<String>> shown = outRows.subList(0, Math.min(limit, outRows.size()));
        String header = outRows.size() + " row(s)" + (shown.size() < outRows.size() ? ", showing the first " + shown.size() : "")
                + " (from " + table.rows().size() + " in " + path + ")." + saved;
        return header + "\n" + Csv.write(outColumns, shown);
    }

    private static Csv.Table load(WorkspaceFile file) {
        if (!file.isText()) {
            throw new ToolInputException(file.path() + " is a binary file (" + file.mediaType()
                    + "); data_query reads CSV or JSON");
        }
        boolean json = file.mediaType().contains("json") || file.path().toLowerCase(Locale.ROOT).endsWith(".json");
        return json ? fromJson(file) : Csv.parse(file.text());
    }

    private static Csv.Table fromJson(WorkspaceFile file) {
        List<Map<String, Object>> records;
        try {
            Object parsed = JSON.readValue(file.text(), Object.class);
            if (!(parsed instanceof List<?> list) || !list.stream().allMatch(Map.class::isInstance)) {
                throw new ToolInputException(file.path() + " must contain a JSON array of objects");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
            records = typed;
        } catch (JacksonException e) {
            throw new ToolInputException(file.path() + " isn't valid JSON: " + e.getOriginalMessage());
        }
        Set<String> columns = new LinkedHashSet<>();
        records.forEach(record -> columns.addAll(record.keySet()));
        List<String> columnList = List.copyOf(columns);
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            List<String> row = new ArrayList<>();
            for (String column : columnList) {
                Object value = record.get(column);
                row.add(value == null ? "" : value instanceof Map || value instanceof List
                        ? JSON.writeValueAsString(value) : String.valueOf(value));
            }
            rows.add(row);
        }
        return new Csv.Table(columnList, rows);
    }

    private static List<List<String>> filter(Csv.Table table, List<Map<String, Object>> conditions) {
        List<List<String>> rows = table.rows();
        for (Map<String, Object> condition : conditions) {
            int at = index(table, ToolArguments.requireString(condition, "column"));
            String op = ToolArguments.requireString(condition, "op");
            if (!OPERATORS.contains(op)) {
                throw new ToolInputException("Unknown op '" + op + "'; use one of " + OPERATORS);
            }
            String expected = ToolArguments.optionalString(condition, "value");
            if (expected == null && !op.equals("is_empty") && !op.equals("not_empty")) {
                throw new ToolInputException("op '" + op + "' needs a value");
            }
            rows = rows.stream().filter(row -> matches(row.get(at), op, expected)).toList();
        }
        return rows;
    }

    private static boolean matches(String actual, String op, String expected) {
        return switch (op) {
            case "is_empty" -> actual.isBlank();
            case "not_empty" -> !actual.isBlank();
            case "contains" -> actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "starts_with" -> actual.toLowerCase(Locale.ROOT).startsWith(expected.toLowerCase(Locale.ROOT));
            default -> {
                int comparison = compareValues(actual, expected);
                yield switch (op) {
                    case "=" -> comparison == 0;
                    case "!=" -> comparison != 0;
                    case ">" -> comparison > 0;
                    case ">=" -> comparison >= 0;
                    case "<" -> comparison < 0;
                    case "<=" -> comparison <= 0;
                    default -> throw new IllegalStateException(op);
                };
            }
        };
    }

    private static List<List<String>> aggregate(
            Csv.Table table, List<List<String>> rows, List<String> groupBy, List<Map<String, Object>> aggregates) {
        List<Integer> groupIndexes = groupBy.stream().map(column -> index(table, column)).toList();
        Map<List<String>, List<List<String>>> groups = new LinkedHashMap<>();
        for (List<String> row : rows) {
            groups.computeIfAbsent(groupIndexes.stream().map(row::get).toList(), key -> new ArrayList<>()).add(row);
        }
        if (groups.isEmpty() && groupBy.isEmpty()) {
            groups.put(List.of(), List.of());
        }

        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<List<String>, List<List<String>>> group : groups.entrySet()) {
            List<String> row = new ArrayList<>(group.getKey());
            for (Map<String, Object> aggregate : aggregates) {
                row.add(compute(table, group.getValue(), aggregate));
            }
            out.add(row);
        }
        return out;
    }

    private static String compute(Csv.Table table, List<List<String>> rows, Map<String, Object> aggregate) {
        String function = ToolArguments.requireString(aggregate, "function");
        if (!FUNCTIONS.contains(function)) {
            throw new ToolInputException("Unknown aggregate function '" + function + "'; use one of " + FUNCTIONS);
        }
        String column = ToolArguments.optionalString(aggregate, "column");
        if (column == null || column.isBlank()) {
            if (!function.equals("count")) {
                throw new ToolInputException(function + " needs a column");
            }
            return String.valueOf(rows.size());
        }
        int at = index(table, column);
        List<String> values = rows.stream().map(row -> row.get(at)).filter(value -> !value.isBlank()).toList();
        if (function.equals("count")) {
            return String.valueOf(values.size());
        }
        List<BigDecimal> numbers = values.stream().map(DataTools::number).filter(n -> n != null).toList();
        if (numbers.isEmpty()) {
            if (function.equals("min") || function.equals("max")) {
                return values.stream()
                        .reduce((a, b) -> (a.compareToIgnoreCase(b) <= 0) == function.equals("min") ? a : b)
                        .orElse("");
            }
            return "";
        }
        BigDecimal result = switch (function) {
            case "sum" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(numbers.size()), MathContext.DECIMAL64);
            case "min" -> numbers.stream().reduce(BigDecimal::min).orElseThrow();
            case "max" -> numbers.stream().reduce(BigDecimal::max).orElseThrow();
            default -> throw new IllegalStateException(function);
        };
        return Calculator.format(result);
    }

    private static String aggregateName(Map<String, Object> aggregate) {
        String as = ToolArguments.optionalString(aggregate, "as");
        if (as != null && !as.isBlank()) {
            return as;
        }
        String column = ToolArguments.optionalString(aggregate, "column");
        String function = String.valueOf(aggregate.get("function"));
        return column == null || column.isBlank() ? function : function + "_" + column;
    }

    /** Numerically when both sides are numbers, otherwise as case-insensitive text. */
    static int compareValues(String a, String b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        return a.strip().compareToIgnoreCase(b.strip());
    }

    /**
     * Lenient number parsing for real-world spreadsheets: ignores currency symbols, thousands
     * separators, a trailing %, and surrounding spaces; {@code (300)} means -300. {@code null} if
     * it isn't a number.
     */
    static BigDecimal number(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip().replaceAll("[£$€¥,\\s]", "");
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1);
        }
        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true;
            text = text.substring(1, text.length() - 1);
        }
        if (!text.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) {
            return null;
        }
        BigDecimal number = new BigDecimal(text);
        return negative ? number.negate() : number;
    }

    private static int index(Csv.Table table, String column) {
        int at = table.columns().indexOf(column);
        if (at < 0) {
            for (int i = 0; i < table.columns().size(); i++) {
                if (table.columns().get(i).equalsIgnoreCase(column)) {
                    return i;
                }
            }
            throw new ToolInputException("No column '" + column + "'; columns are " + table.columns());
        }
        return at;
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
}
