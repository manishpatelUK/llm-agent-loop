package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
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
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentCreationTest {

    private static final String MARKDOWN = """
            # Mutual NDA

            This agreement is between **Acme Ltd** and *Globex*, see [our terms](https://example.com/terms).

            ## Obligations

            - Keep information confidential
              - Including `source code`
            - Return materials on request

            1. Term: two years
            2. Governing law: England

            > Signed in good faith.

            | Party | Signatory |
            | --- | --- |
            | Acme | A. Smith |

            ```
            clause = "verbatim"
            ```

            ---

            <!-- pagebreak -->

            Appendix text.
            """;

    private ToolContext context;

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void parsesTheSupportedMarkdown() {
        List<Block> blocks = MarkdownBlocks.parse(MARKDOWN);

        assertThat(blocks).extracting(b -> b.getClass().getSimpleName()).containsExactly(
                "Heading", "Paragraph", "Heading", "ListItem", "ListItem", "ListItem", "ListItem", "ListItem",
                "Quote", "Table", "Code", "Rule", "PageBreak", "Paragraph");
        assertThat(((Heading) blocks.get(0)).level()).isEqualTo(1);
        List<Span> spans = ((Paragraph) blocks.get(1)).spans();
        assertThat(spans).anyMatch(s -> s.text().equals("Acme Ltd") && s.bold());
        assertThat(spans).anyMatch(s -> s.text().equals("Globex") && s.italic());
        assertThat(spans).anyMatch(s -> s.text().equals("our terms") && "https://example.com/terms".equals(s.link()));
        assertThat(((ListItem) blocks.get(4)).level()).isEqualTo(1);
        assertThat(((ListItem) blocks.get(4)).spans()).anyMatch(s -> s.code() && s.text().equals("source code"));
        assertThat(((ListItem) blocks.get(6)).number()).isEqualTo(1);
        assertThat(((Table) blocks.get(9)).rows()).hasSize(2);
        assertThat(((Code) blocks.get(10)).text()).isEqualTo("clause = \"verbatim\"");
        assertThat(blocks.get(8)).isInstanceOf(Quote.class);
        assertThat(blocks.get(11)).isInstanceOf(Rule.class);
        assertThat(blocks.get(12)).isInstanceOf(PageBreak.class);
    }

    @Test
    void createsAWordDocumentWithStylesListsTablesAndLinks() throws IOException {
        String result = create(Map.of("path", "legal/nda.docx", "markdown", MARKDOWN, "title", "Mutual NDA"));

        assertThat(result).startsWith("Created legal/nda.docx");
        assertThat(context.workspace().require("legal/nda.docx").mediaType()).isEqualTo(MediaTypes.DOCX);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(context.workspace().require("legal/nda.docx").content()))) {
            assertThat(doc.getProperties().getCoreProperties().getTitle()).isEqualTo("Mutual NDA");
            XWPFParagraph heading = doc.getParagraphs().getFirst();
            assertThat(heading.getText()).isEqualTo("Mutual NDA");
            assertThat(heading.getRuns().getFirst().isBold()).isTrue();
            assertThat(doc.getParagraphs()).anyMatch(p -> p.getText().equals("•\tKeep information confidential"));
            assertThat(doc.getParagraphs()).anyMatch(p -> p.getText().equals("1.\tTerm: two years"));
            assertThat(doc.getParagraphs()).anyMatch(p -> p.getRuns().stream()
                    .anyMatch(r -> r instanceof XWPFHyperlinkRun link
                            && doc.getHyperlinkByID(link.getHyperlinkId()).getURL().equals("https://example.com/terms")));
            assertThat(doc.getTables()).singleElement().satisfies(table -> {
                assertThat(table.getRow(0).getCell(0).getText()).isEqualTo("Party");
                assertThat(table.getRow(1).getCell(1).getText()).isEqualTo("A. Smith");
            });
        }
        assertThat(DocumentTools.read().handler().handle(new HashMap<>(Map.of("path", "legal/nda.docx")), context))
                .contains("Mutual NDA", "Keep information confidential", "Appendix text.");
    }

    @Test
    void createsAPdfWithTextLinksAndPageBreaks() throws IOException {
        create(Map.of("path", "legal/nda.pdf", "markdown", MARKDOWN + "\nCurrency £100 and emoji 🎉.", "title", "Mutual NDA"));

        byte[] pdf = context.workspace().require("legal/nda.pdf").content();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Mutual NDA");
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Mutual NDA", "Acme Ltd", "Keep information confidential", "1.", "Term: two years",
                    "Party", "A. Smith", "clause = \"verbatim\"", "Appendix text.", "£100");
            assertThat(text).doesNotContain("🎉");
            assertThat(doc.getPage(0).getAnnotations()).anyMatch(a -> a instanceof PDAnnotationLink);
        }
    }

    @Test
    void longDocumentsFlowOntoMorePages() throws IOException {
        create(Map.of("path", "long.pdf", "markdown", "Lorem ipsum dolor sit amet. ".repeat(2_000)));

        try (PDDocument doc = Loader.loadPDF(context.workspace().require("long.pdf").content())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(5);
        }
    }

    @Test
    void theFormatComesFromTheExtension() {
        assertThatThrownBy(() -> create(Map.of("path", "notes.txt", "markdown", "x")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining(".docx or .pdf");
    }

    @Test
    void createsAPresentationWithTitleContentNestedBulletsAndNotes() throws IOException {
        String result = PresentationTools.create().handler().handle(new HashMap<>(Map.of("path", "pitch/deck.pptx", "slides", List.of(
                Map.of("layout", "title", "title", "Acme", "subtitle", "Seed round"),
                Map.of("title", "Market", "bullets", List.of("Large", "  Growing 20% a year", "Underserved"),
                        "notes", "Mention the TAM figure")))), context);

        assertThat(result).startsWith("Created pitch/deck.pptx (2 slides");
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(context.workspace().require("pitch/deck.pptx").content()))) {
            List<XSLFSlide> slides = show.getSlides();
            assertThat(slides).hasSize(2);
            assertThat(slides.get(0).getPlaceholder(0).getText()).isEqualTo("Acme"); // title layouts use a centred-title placeholder
            assertThat(slides.get(0).getPlaceholder(1).getText()).isEqualTo("Seed round");
            assertThat(slides.get(1).getTitle()).isEqualTo("Market");
            XSLFTextShape body = slides.get(1).getPlaceholder(1);
            assertThat(body.getTextParagraphs()).extracting(XSLFTextParagraph::getText)
                    .containsExactly("Large", "Growing 20% a year", "Underserved");
            assertThat(body.getTextParagraphs().get(1).getIndentLevel()).isEqualTo(1);
            assertThat(show.getNotesSlide(slides.get(1)).getPlaceholder(1).getText()).isEqualTo("Mention the TAM figure");
        }
        assertThat(DocumentTools.read().handler().handle(new HashMap<>(Map.of("path", "pitch/deck.pptx")), context))
                .contains("## Slide 2: Market", "Growing 20% a year", "Notes: Mention the TAM figure");
    }

    @Test
    void presentationMistakesAreFixable() {
        assertThatThrownBy(() -> PresentationTools.create().handler().handle(new HashMap<>(Map.of("path", "d.pptx",
                "slides", List.of(Map.of("bullets", List.of("x"))))), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Slide 1");
        assertThatThrownBy(() -> PresentationTools.create().handler().handle(new HashMap<>(Map.of("path", "d.ppt",
                "slides", List.of(Map.of("title", "x")))), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining(".pptx");
    }

    private String create(Map<String, Object> args) {
        return DocumentTools.create().handler().handle(new HashMap<>(args), context);
    }
}
