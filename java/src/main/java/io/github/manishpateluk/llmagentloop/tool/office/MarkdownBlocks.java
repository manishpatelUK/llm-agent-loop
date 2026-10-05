package io.github.manishpateluk.llmagentloop.tool.office;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown subset {@code document_create} understands, parsed into blocks of styled text that
 * the Word and PDF writers render. Headings ({@code #}..{@code ######}), paragraphs, bullet and
 * numbered lists (nested by indentation), block quotes, fenced code, pipe tables, horizontal rules
 * ({@code ---}), page breaks ({@code <!-- pagebreak -->}); inline {@code **bold**}, {@code *italic*},
 * {@code `code`} and {@code [links](https://...)}.
 */
final class MarkdownBlocks {

    private MarkdownBlocks() {
    }

    /** A run of text with one style. */
    record Span(String text, boolean bold, boolean italic, boolean code, String link) {
    }

    sealed interface Block permits Heading, Paragraph, ListItem, Quote, Code, Table, Rule, PageBreak {
    }

    record Heading(int level, List<Span> spans) implements Block {
    }

    record Paragraph(List<Span> spans) implements Block {
    }

    /** {@code number} is {@code null} for a bullet. */
    record ListItem(int level, Integer number, List<Span> spans) implements Block {
    }

    record Quote(List<Span> spans) implements Block {
    }

    record Code(String text) implements Block {
    }

    /** The first row is the header. */
    record Table(List<List<List<Span>>> rows) implements Block {
    }

    record Rule() implements Block {
    }

    record PageBreak() implements Block {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)"
                    + "|`([^`]+)`|\\[([^\\]]+)]\\(([^)\\s]+)\\)");

    static List<Block> parse(String markdown) {
        List<String> lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                flush(paragraph, blocks);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.size() && !lines.get(i).strip().startsWith("```"); i++) {
                    code.append(lines.get(i)).append('\n');
                }
                blocks.add(new Code(code.toString().stripTrailing()));
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(paragraph, blocks);
                continue;
            }
            if (trimmed.equalsIgnoreCase("<!-- pagebreak -->") || trimmed.equalsIgnoreCase("\\pagebreak")) {
                flush(paragraph, blocks);
                blocks.add(new PageBreak());
                continue;
            }
            if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                flush(paragraph, blocks);
                blocks.add(new Rule());
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flush(paragraph, blocks);
                blocks.add(new Heading(heading.group(1).length(), spans(heading.group(2))));
                continue;
            }
            if (trimmed.startsWith("|") && i + 1 < lines.size() && TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches()) {
                flush(paragraph, blocks);
                List<List<List<Span>>> rows = new ArrayList<>();
                rows.add(cells(trimmed));
                for (i += 2; i < lines.size() && lines.get(i).strip().startsWith("|"); i++) {
                    rows.add(cells(lines.get(i).strip()));
                }
                i--;
                blocks.add(new Table(rows));
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flush(paragraph, blocks);
                blocks.add(new ListItem(level(bullet.group(1)), null, spans(bullet.group(2))));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                flush(paragraph, blocks);
                blocks.add(new ListItem(level(numbered.group(1)), Integer.parseInt(numbered.group(2)), spans(numbered.group(3))));
                continue;
            }
            if (trimmed.startsWith(">")) {
                flush(paragraph, blocks);
                StringBuilder quote = new StringBuilder(trimmed.substring(1).strip());
                while (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith(">")) {
                    quote.append(' ').append(lines.get(++i).strip().substring(1).strip());
                }
                blocks.add(new Quote(spans(quote.toString())));
                continue;
            }
            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        flush(paragraph, blocks);
        return blocks;
    }

    private static void flush(StringBuilder paragraph, List<Block> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Paragraph(spans(paragraph.toString())));
            paragraph.setLength(0);
        }
    }

    /** Two spaces (or a tab) of indentation per nesting level. */
    private static int level(String indent) {
        int width = indent.replace("\t", "  ").length();
        return Math.min(width / 2, 5);
    }

    private static List<List<Span>> cells(String row) {
        String inner = row.strip();
        if (inner.startsWith("|")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        List<List<Span>> cells = new ArrayList<>();
        for (String cell : inner.split("(?<!\\\\)\\|", -1)) {
            cells.add(spans(cell.strip().replace("\\|", "|")));
        }
        return cells;
    }

    static List<Span> spans(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher m = INLINE.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                spans.add(plain(text.substring(at, m.start())));
            }
            if (m.group(1) != null || m.group(2) != null) {
                String inner = m.group(1) != null ? m.group(1) : m.group(2);
                for (Span span : spans(inner)) {
                    spans.add(new Span(span.text(), true, span.italic(), span.code(), span.link()));
                }
            } else if (m.group(3) != null || m.group(4) != null) {
                String inner = m.group(3) != null ? m.group(3) : m.group(4);
                for (Span span : spans(inner)) {
                    spans.add(new Span(span.text(), span.bold(), true, span.code(), span.link()));
                }
            } else if (m.group(5) != null) {
                spans.add(new Span(m.group(5), false, false, true, null));
            } else {
                spans.add(new Span(m.group(6), false, false, false, m.group(7)));
            }
            at = m.end();
        }
        if (at < text.length()) {
            spans.add(plain(text.substring(at)));
        }
        return spans;
    }

    private static Span plain(String text) {
        return new Span(text, false, false, false, null);
    }

    static String plainText(List<Span> spans) {
        StringBuilder out = new StringBuilder();
        spans.forEach(span -> out.append(span.text()));
        return out.toString();
    }
}
