package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentToolsTest {

    private ToolContext context;

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void readsPdfTextWithPageRanges() throws IOException {
        context.workspace().write("uploads/report.pdf", pdf("Revenue grew 20%", "Costs fell", "Outlook strong"), null);

        assertThat(read(Map.of("path", "uploads/report.pdf")))
                .startsWith("uploads/report.pdf: PDF, 3 page(s).").contains("Revenue grew 20%", "Costs fell", "Outlook strong");
        assertThat(read(Map.of("path", "uploads/report.pdf", "pages", "2-3")))
                .contains("showing pages 2-3", "Costs fell", "Outlook strong").doesNotContain("Revenue");
        assertThat(read(Map.of("path", "uploads/report.pdf", "pages", "1"))).contains("Revenue").doesNotContain("Costs");
        assertThatThrownBy(() -> read(Map.of("path", "uploads/report.pdf", "pages", "4")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("3 page(s)");
    }

    @Test
    void aPdfWithoutTextPointsTheModelToWorkspaceView() throws IOException {
        context.workspace().write("scan.pdf", pdf(), null);

        assertThat(read(Map.of("path", "scan.pdf"))).contains("no extractable text", "workspace_view");
    }

    @Test
    void readsWordDocuments() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Mutual NDA");
            document.createParagraph().createRun().setText("Clause 4: term of two years.");
            document.write(out);
            context.workspace().write("nda.docx", out.toByteArray(), null);
        }

        assertThat(read(Map.of("path", "nda.docx"))).contains("Mutual NDA", "Clause 4: term of two years.");
    }

    @Test
    void readsPowerPointSlidesAndNotes() throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("Our market is large");
            show.getNotesSlide(slide).getPlaceholder(1).setText("Mention the TAM figure");
            show.write(out);
            context.workspace().write("pitch.pptx", out.toByteArray(), null);
        }

        assertThat(read(Map.of("path", "pitch.pptx"))).contains("## Slide 1", "Our market is large", "Notes: Mention the TAM figure");
    }

    @Test
    void longDocumentsArePaged() throws IOException {
        context.workspace().writeText("long.md", "abcdefghij");

        assertThat(read(Map.of("path", "long.md", "max_chars", 4))).startsWith("abcd").contains("offset 4");
        assertThat(read(Map.of("path", "long.md", "offset", 4))).isEqualTo("efghij");
    }

    @Test
    void otherTypesPointToTheRightTool() {
        context.workspace().write("photo.png", new byte[]{1}, null);
        context.workspace().write("model.xlsx", new byte[]{1}, null);
        context.workspace().write("broken.pdf", "not a pdf".getBytes(), "application/pdf");

        assertThatThrownBy(() -> read(Map.of("path", "photo.png"))).hasMessageContaining("workspace_view");
        assertThatThrownBy(() -> read(Map.of("path", "model.xlsx"))).hasMessageContaining("spreadsheet_read");
        assertThatThrownBy(() -> read(Map.of("path", "broken.pdf"))).hasMessageContaining("isn't a readable PDF");
        assertThatThrownBy(() -> read(Map.of("path", "missing.pdf"))).hasMessageContaining("No such file");
    }

    private String read(Map<String, Object> args) {
        return DocumentTools.read().handler().handle(new HashMap<>(args), context);
    }

    /** A PDF with one page per string of text (no strings: one blank page). */
    private static byte[] pdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (pages.length == 0) {
                document.addPage(new PDPage());
            }
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
