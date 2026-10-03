package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV reading and writing — quoted fields, escaped quotes, newlines inside quotes. */
final class Csv {

    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t', '|'};

    private Csv() {
    }

    /** A header row plus data rows; every row is padded or trimmed to the header's width. */
    record Table(List<String> columns, List<List<String>> rows) {
    }

    static Table parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = detectDelimiter(text);
        List<List<String>> records = records(text, delimiter);
        if (records.isEmpty()) {
            throw new ToolInputException("The file has no header row");
        }
        List<String> columns = records.getFirst().stream().map(String::strip).toList();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() == 1 && record.getFirst().isBlank()) {
                continue;
            }
            List<String> row = new ArrayList<>(record.subList(0, Math.min(record.size(), columns.size())));
            while (row.size() < columns.size()) {
                row.add("");
            }
            rows.add(row);
        }
        return new Table(columns, rows);
    }

    static String write(List<String> columns, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        appendRow(out, columns);
        for (List<String> row : rows) {
            appendRow(out, row);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                out.append(value);
            }
        }
        out.append('\n');
    }

    /** Whichever candidate appears most often (outside quotes) in the header line. */
    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            long count = header.chars().filter(c -> c == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
                fieldStarted = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (fieldStarted || !field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
