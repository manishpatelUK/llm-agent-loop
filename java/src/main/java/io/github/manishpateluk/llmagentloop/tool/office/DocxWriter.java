package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Block;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Code;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Heading;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.ListItem;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.PageBreak;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Paragraph;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Quote;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Rule;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Span;
import io.github.manishpateluk.llmagentloop.tool.office.MarkdownBlocks.Table;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Renders {@link MarkdownBlocks} as a Word document. */
final class DocxWriter {

    private static final int[] HEADING_SIZES = {20, 16, 14, 13, 12, 11};

    private DocxWriter() {
    }

    static byte[] write(String title, List<Block> blocks) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                document.getProperties().getCoreProperties().setTitle(title);
            }
            for (Block block : blocks) {
                render(document, block);
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void render(XWPFDocument document, Block block) {
        switch (block) {
            case Heading heading -> {
                XWPFParagraph p = document.createParagraph();
                p.setSpacingBefore(240);
                p.setSpacingAfter(120);
                for (Span span : heading.spans()) {
                    XWPFRun run = run(p, span);
                    run.setBold(true);
                    run.setFontSize(HEADING_SIZES[heading.level() - 1]);
                }
            }
            case Paragraph paragraph -> spans(document.createParagraph(), paragraph.spans());
            case ListItem item -> {
                XWPFParagraph p = document.createParagraph();
                p.setIndentationLeft(360 * (item.level() + 1));
                p.setIndentationHanging(360);
                XWPFRun marker = p.createRun();
                marker.setText(item.number() == null ? "•\t" : item.number() + ".\t");
                spans(p, item.spans());
            }
            case Quote quote -> {
                XWPFParagraph p = document.createParagraph();
                p.setIndentationLeft(720);
                p.setBorderLeft(Borders.SINGLE);
                for (Span span : quote.spans()) {
                    run(p, span).setItalic(true);
                }
            }
            case Code code -> {
                XWPFParagraph p = document.createParagraph();
                p.setIndentationLeft(360);
                String[] lines = code.text().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    XWPFRun run = p.createRun();
                    run.setFontFamily("Courier New");
                    run.setFontSize(9);
                    run.setText(lines[i]);
                    if (i < lines.length - 1) {
                        run.addBreak();
                    }
                }
            }
            case Table table -> renderTable(document, table);
            case Rule rule -> document.createParagraph().setBorderBottom(Borders.SINGLE);
            case PageBreak pageBreak -> document.createParagraph().createRun().addBreak(BreakType.PAGE);
        }
    }

    private static void renderTable(XWPFDocument document, Table table) {
        int columns = table.rows().stream().mapToInt(List::size).max().orElse(1);
        XWPFTable grid = document.createTable(table.rows().size(), columns);
        grid.setWidth("100%");
        for (int r = 0; r < table.rows().size(); r++) {
            XWPFTableRow row = grid.getRow(r);
            List<List<Span>> cells = table.rows().get(r);
            for (int c = 0; c < columns; c++) {
                XWPFTableCell cell = row.getCell(c);
                XWPFParagraph p = cell.getParagraphs().getFirst();
                if (c < cells.size()) {
                    for (Span span : cells.get(c)) {
                        XWPFRun run = run(p, span);
                        if (r == 0) {
                            run.setBold(true);
                        }
                    }
                }
            }
        }
        document.createParagraph();
    }

    private static void spans(XWPFParagraph p, List<Span> spans) {
        for (Span span : spans) {
            run(p, span);
        }
    }

    private static XWPFRun run(XWPFParagraph p, Span span) {
        XWPFRun run;
        if (span.link() != null) {
            XWPFHyperlinkRun link = p.createHyperlinkRun(span.link());
            link.setColor("0563C1");
            link.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
            run = link;
        } else {
            run = p.createRun();
        }
        run.setText(span.text());
        run.setBold(span.bold());
        run.setItalic(span.italic());
        if (span.code()) {
            run.setFontFamily("Courier New");
        }
        return run;
    }
}
