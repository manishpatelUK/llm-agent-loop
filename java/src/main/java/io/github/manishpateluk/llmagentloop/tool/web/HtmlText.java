package io.github.manishpateluk.llmagentloop.tool.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.Set;

/**
 * Turns an HTML page into compact, readable Markdown-ish text for a model: headings, paragraphs,
 * lists, links (absolute), code blocks and simple tables kept; scripts, styles, navigation chrome
 * and markup dropped. Prefers the page's {@code <main>} or {@code <article>} when it has one.
 */
final class HtmlText {

    private static final Set<String> DROPPED = Set.of(
            "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed",
            "nav", "footer", "aside", "form", "button", "select", "input", "head");
    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "section", "article", "main", "header", "blockquote", "figure", "figcaption",
            "dl", "dt", "dd", "address", "details", "summary");

    private HtmlText() {
    }

    record Page(String title, String text) {
    }

    static Page convert(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri);
        Element root = document.selectFirst("main");
        if (root == null) {
            root = document.selectFirst("article");
        }
        if (root == null) {
            root = document.body();
        }
        StringBuilder out = new StringBuilder();
        render(root, out, 0);
        return new Page(document.title().strip(), tidy(out.toString()));
    }

    private static void render(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode text) {
            String value = text.getWholeText().replaceAll("\\s+", " ");
            if (!value.isBlank() || (!out.isEmpty() && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '\n')) {
                out.append(value);
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.normalName();
        if (DROPPED.contains(tag)) {
            return;
        }
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                out.append("\n\n").append("#".repeat(tag.charAt(1) - '0')).append(' ').append(element.text().strip()).append("\n\n");
            }
            case "br" -> out.append('\n');
            case "hr" -> out.append("\n\n---\n\n");
            case "pre" -> out.append("\n\n```\n").append(element.wholeText().strip()).append("\n```\n\n");
            case "code" -> out.append('`').append(element.text()).append('`');
            case "a" -> {
                String label = element.text().strip();
                String href = element.absUrl("href");
                if (label.isEmpty()) {
                    return;
                }
                if (href.isEmpty() || href.startsWith("javascript:")) {
                    out.append(label);
                } else {
                    out.append('[').append(label).append("](").append(href).append(')');
                }
            }
            case "img" -> {
                String alt = element.attr("alt").strip();
                if (!alt.isEmpty()) {
                    out.append("[image: ").append(alt).append(']');
                }
            }
            case "ul", "ol" -> {
                out.append('\n');
                int index = 1;
                for (Element item : element.children()) {
                    if (!item.normalName().equals("li")) {
                        continue;
                    }
                    out.append("  ".repeat(listDepth)).append(tag.equals("ol") ? index++ + ". " : "- ");
                    for (Node child : item.childNodes()) {
                        render(child, out, listDepth + 1);
                    }
                    out.append('\n');
                }
                out.append('\n');
            }
            case "table" -> renderTable(element, out);
            case "strong", "b" -> out.append("**").append(element.text().strip()).append("**");
            case "em", "i" -> out.append('_').append(element.text().strip()).append('_');
            default -> {
                boolean block = BLOCKS.contains(tag) || tag.equals("li") || tag.equals("body");
                if (block) {
                    out.append("\n\n");
                }
                for (Node child : element.childNodes()) {
                    render(child, out, listDepth);
                }
                if (block) {
                    out.append("\n\n");
                }
            }
        }
    }

    private static void renderTable(Element table, StringBuilder out) {
        out.append("\n\n");
        boolean first = true;
        for (Element row : table.select("tr")) {
            StringBuilder line = new StringBuilder("|");
            int cells = 0;
            for (Element cell : row.children()) {
                String name = cell.normalName();
                if (name.equals("td") || name.equals("th")) {
                    line.append(' ').append(cell.text().strip().replace("|", "\\|")).append(" |");
                    cells++;
                }
            }
            if (cells == 0) {
                continue;
            }
            out.append(line).append('\n');
            if (first) {
                out.append("|").append(" --- |".repeat(cells)).append('\n');
                first = false;
            }
        }
        out.append('\n');
    }

    /** Trims each line and collapses runs of blank lines to one. */
    private static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        boolean blank = true;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (!blank) {
                    out.append('\n');
                }
                blank = true;
            } else {
                // Keep list indentation, which strip() would remove.
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                out.append(indent.startsWith(" ") && trimmed.matches("^(- |\\d+\\. ).*") ? indent : "")
                        .append(trimmed).append('\n');
                blank = false;
            }
        }
        return out.toString().strip();
    }
}
