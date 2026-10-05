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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@link MarkdownBlocks} as an A4 PDF with PDFBox's standard fonts: wrapped text, styled
 * runs, lists, quotes, code blocks, simple bordered tables, clickable links and page breaks.
 * Characters the standard fonts can't encode (most non-Latin scripts, emoji) are replaced with
 * {@code ?} — a limitation of not embedding a font.
 */
final class PdfWriter {

    private static final float MARGIN = 56;
    private static final float BODY_SIZE = 11;
    private static final float[] HEADING_SIZES = {20, 16, 14, 12.5f, 12, 11};
    private static final float CELL_PADDING = 4;

    private final PDDocument document = new PDDocument();
    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDFont italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    private final PDFont boldItalic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
    private final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private final Map<PDFont, Map<Character, Boolean>> encodable = new HashMap<>();
    private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private PDPage page;
    private PDPageContentStream content;
    private float y;

    private PdfWriter() {
    }

    static byte[] write(String title, List<Block> blocks) {
        PdfWriter writer = new PdfWriter();
        try {
            return writer.render(title, blocks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] render(String title, List<Block> blocks) throws IOException {
        try (document; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle(title);
                document.setDocumentInformation(info);
            }
            newPage();
            for (Block block : blocks) {
                block(block);
            }
            content.close();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void block(Block block) throws IOException {
        switch (block) {
            case Heading heading -> {
                float size = HEADING_SIZES[heading.level() - 1];
                gap(size * 0.8f);
                flow(heading.spans(), size, 0, true, false);
                gap(size * 0.3f);
            }
            case Paragraph paragraph -> {
                flow(paragraph.spans(), BODY_SIZE, 0, false, false);
                gap(BODY_SIZE * 0.6f);
            }
            case ListItem item -> {
                float indent = 18 * (item.level() + 1);
                String marker = item.number() == null ? "•" : item.number() + ".";
                ensure(BODY_SIZE * 1.4f);
                text(MARGIN + indent - 14, y - BODY_SIZE, regular, BODY_SIZE, marker, Color.BLACK);
                flow(item.spans(), BODY_SIZE, indent, false, false);
                gap(BODY_SIZE * 0.25f);
            }
            case Quote quote -> {
                float top = y;
                flow(quote.spans(), BODY_SIZE, 16, false, true);
                content.setStrokingColor(Color.LIGHT_GRAY);
                content.setLineWidth(2);
                content.moveTo(MARGIN + 6, Math.min(top, PDRectangle.A4.getHeight() - MARGIN));
                content.lineTo(MARGIN + 6, y);
                content.stroke();
                gap(BODY_SIZE * 0.6f);
            }
            case Code code -> {
                for (String line : code.text().split("\n", -1)) {
                    ensure(10 * 1.35f);
                    y -= 10 * 1.35f;
                    text(MARGIN + 8, y + 3, mono, 9, line, Color.DARK_GRAY);
                }
                gap(BODY_SIZE * 0.6f);
            }
            case Table table -> table(table);
            case Rule rule -> {
                gap(6);
                ensure(8);
                content.setStrokingColor(Color.GRAY);
                content.setLineWidth(0.5f);
                content.moveTo(MARGIN, y);
                content.lineTo(MARGIN + width, y);
                content.stroke();
                gap(8);
            }
            case PageBreak pageBreak -> {
                content.close();
                newPage();
            }
        }
    }

    /** Word-wraps styled spans across lines (and pages) within the text column, minus {@code indent}. */
    private void flow(List<Span> spans, float size, float indent, boolean forceBold, boolean forceItalic) throws IOException {
        float lineHeight = size * 1.4f;
        float left = MARGIN + indent;
        float available = width - indent;
        List<Word> line = new ArrayList<>();
        float lineWidth = 0;
        for (Span span : spans) {
            PDFont font = font(span, forceBold, forceItalic);
            String[] words = span.text().split("(?<= )");
            for (String raw : words) {
                if (raw.isEmpty()) {
                    continue;
                }
                String word = encode(font, raw);
                float w = font.getStringWidth(word) / 1000 * size;
                if (lineWidth + w > available && !line.isEmpty()) {
                    emitLine(line, left, size, lineHeight);
                    line.clear();
                    lineWidth = 0;
                    word = word.stripLeading();
                    w = font.getStringWidth(word) / 1000 * size;
                }
                line.add(new Word(word, font, w, span.link()));
                lineWidth += w;
            }
        }
        if (!line.isEmpty()) {
            emitLine(line, left, size, lineHeight);
        }
    }

    private record Word(String text, PDFont font, float width, String link) {
    }

    private void emitLine(List<Word> words, float left, float size, float lineHeight) throws IOException {
        ensure(lineHeight);
        y -= lineHeight;
        float x = left;
        for (Word word : words) {
            Color color = word.link() != null ? new Color(5, 99, 193) : Color.BLACK;
            text(x, y + (lineHeight - size) / 2, word.font(), size, word.text(), color);
            if (word.link() != null && !word.text().isBlank()) {
                link(x, y, word.width(), lineHeight, word.link());
            }
            x += word.width();
        }
    }

    private void table(Table table) throws IOException {
        int columns = table.rows().stream().mapToInt(List::size).max().orElse(1);
        float columnWidth = width / columns;
        float size = BODY_SIZE - 1;
        float lineHeight = size * 1.35f;
        for (int r = 0; r < table.rows().size(); r++) {
            List<List<Span>> row = table.rows().get(r);
            List<List<String>> wrapped = new ArrayList<>();
            int maxLines = 1;
            for (int c = 0; c < columns; c++) {
                PDFont font = r == 0 ? bold : regular;
                String text = c < row.size() ? MarkdownBlocks.plainText(row.get(c)) : "";
                List<String> lines = wrap(encode(font, text), font, size, columnWidth - 2 * CELL_PADDING);
                wrapped.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }
            float rowHeight = maxLines * lineHeight + 2 * CELL_PADDING;
            ensure(rowHeight);
            float top = y;
            for (int c = 0; c < columns; c++) {
                float x = MARGIN + c * columnWidth;
                content.setStrokingColor(Color.GRAY);
                content.setLineWidth(0.5f);
                content.addRect(x, top - rowHeight, columnWidth, rowHeight);
                content.stroke();
                List<String> lines = wrapped.get(c);
                for (int l = 0; l < lines.size(); l++) {
                    text(x + CELL_PADDING, top - CELL_PADDING - (l + 1) * lineHeight + (lineHeight - size) / 2,
                            r == 0 ? bold : regular, size, lines.get(l), Color.BLACK);
                }
            }
            y = top - rowHeight;
        }
        gap(BODY_SIZE * 0.8f);
    }

    private List<String> wrap(String text, PDFont font, float size, float max) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.getStringWidth(candidate) / 1000 * size > max) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private void text(float x, float baseline, PDFont font, float size, String text, Color color) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        content.beginText();
        content.setNonStrokingColor(color);
        content.setFont(font, size);
        content.newLineAtOffset(x, baseline);
        content.showText(text);
        content.endText();
    }

    private void link(float x, float bottom, float w, float h, String url) throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, bottom, w, h));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        PDActionURI action = new PDActionURI();
        action.setURI(url);
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private PDFont font(Span span, boolean forceBold, boolean forceItalic) {
        if (span.code()) {
            return mono;
        }
        boolean b = span.bold() || forceBold;
        boolean i = span.italic() || forceItalic;
        return b && i ? boldItalic : b ? bold : i ? italic : regular;
    }

    /** {@code text} with anything {@code font} can't encode replaced by '?'. */
    private String encode(PDFont font, String text) {
        Map<Character, Boolean> known = encodable.computeIfAbsent(font, f -> new HashMap<>());
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.replace('\t', ' ').toCharArray()) {
            boolean ok = known.computeIfAbsent(c, ch -> {
                try {
                    font.encode(String.valueOf(ch));
                    return true;
                } catch (IOException | IllegalArgumentException e) {
                    return false;
                }
            });
            out.append(ok ? c : '?');
        }
        return out.toString();
    }

    private void ensure(float needed) throws IOException {
        if (y - needed < MARGIN) {
            content.close();
            newPage();
        }
    }

    private void gap(float amount) {
        y -= amount;
    }

    private void newPage() throws IOException {
        page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        content = new PDPageContentStream(document, page);
        y = PDRectangle.A4.getHeight() - MARGIN;
    }
}
