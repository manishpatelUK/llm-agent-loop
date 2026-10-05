package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code document_read}: the text of a PDF, Word (.docx) or PowerPoint (.pptx) file in the
 * workspace — typically something the user uploaded — paged so a long document can't flood the
 * context window. Scanned PDFs and images have no text to extract; {@code workspace_view} shows
 * those to the model instead. Spreadsheets have their own tool ({@code spreadsheet_read}).
 * Register with {@code registry.registerAll(DocumentTools.all())}; needs a workspace configured.
 */
public final class DocumentTools {

    public static final String READ = "document_read";
    public static final String CREATE = "document_create";

    /** Largest Markdown source {@code document_create} accepts in one call. */
    static final int MAX_SOURCE_CHARS = 500_000;

    static final int DEFAULT_READ_CHARS = 20_000;
    static final int MAX_READ_CHARS = 100_000;

    private DocumentTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(read(), create());
    }

    public static RegisteredTool create() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CREATE)
                        .description("Create a Word document (.docx) or PDF (.pdf) in the workspace from Markdown, or "
                                + "replace an existing one — for letters, contracts, reports, memos. The format follows the "
                                + "path's extension. Supports headings (#), paragraphs, **bold**, *italic*, `code`, "
                                + "[links](https://...), bullet and numbered lists (indent two spaces to nest), > quotes, "
                                + "``` code blocks, | pipe | tables |, --- rules and <!-- pagebreak -->.")
                        .parameters(ToolSchemas.object(List.of("path", "markdown"),
                                "path", ToolSchemas.string("Workspace path ending in .docx or .pdf, e.g. \"legal/nda-draft.docx\"."),
                                "markdown", ToolSchemas.string("The document's content as Markdown."),
                                "title", ToolSchemas.string("Optional document title, stored in the file's properties.")))
                        .build(),
                DocumentTools::create);
    }

    private static String create(Map<String, Object> args, ToolContext context) {
        String path = ToolArguments.requireString(args, "path");
        String markdown = ToolArguments.requireStringAllowEmpty(args, "markdown");
        String title = ToolArguments.optionalString(args, "title");
        if (markdown.length() > MAX_SOURCE_CHARS) {
            throw new ToolInputException("The document is longer than " + MAX_SOURCE_CHARS + " characters; split it into several files");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        List<MarkdownBlocks.Block> blocks = MarkdownBlocks.parse(markdown);
        byte[] bytes;
        String mediaType;
        if (lower.endsWith(".docx")) {
            bytes = DocxWriter.write(title, blocks);
            mediaType = MediaTypes.DOCX;
        } else if (lower.endsWith(".pdf")) {
            bytes = PdfWriter.write(title, blocks);
            mediaType = "application/pdf";
        } else {
            throw new ToolInputException("'path' must end in .docx or .pdf, was \"" + path + "\"");
        }
        try {
            WorkspaceFile file = context.workspace().write(path, bytes, mediaType);
            return "Created " + file.path() + " (" + file.size() + " bytes, " + blocks.size() + " blocks).";
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        }
    }

    public static RegisteredTool read() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(READ)
                        .description("Read the text of a PDF, Word (.docx) or PowerPoint (.pptx) file in the workspace, "
                                + "such as one the user uploaded. Long documents come back in pieces: use offset to "
                                + "continue, or pages to read part of a PDF. For scanned PDFs or images (no text), use "
                                + "workspace_view to look at them; for spreadsheets, use spreadsheet_read.")
                        .parameters(ToolSchemas.object(List.of("path"),
                                "path", ToolSchemas.string("Workspace path, e.g. \"uploads/contract.pdf\"."),
                                "pages", ToolSchemas.string("PDF only: page or page range, e.g. \"3\" or \"2-5\". Defaults to all pages."),
                                "offset", ToolSchemas.integer("Character to start from. Defaults to 0."),
                                "max_chars", ToolSchemas.integer("Maximum characters to return, up to " + MAX_READ_CHARS
                                        + ". Defaults to " + DEFAULT_READ_CHARS + ".")))
                        .build(),
                DocumentTools::read);
    }

    private static String read(Map<String, Object> args, ToolContext context) {
        WorkspaceFile file;
        try {
            file = context.workspace().require(ToolArguments.requireString(args, "path"));
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        }
        int offset = ToolArguments.optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int maxChars = ToolArguments.optionalInt(args, "max_chars", DEFAULT_READ_CHARS, 1, MAX_READ_CHARS);
        String pages = ToolArguments.optionalString(args, "pages");

        String type = file.mediaType().toLowerCase(Locale.ROOT);
        String path = file.path().toLowerCase(Locale.ROOT);
        Extracted extracted;
        if (type.equals("application/pdf") || path.endsWith(".pdf")) {
            extracted = pdf(file, pages);
        } else if (type.equals(MediaTypes.DOCX) || path.endsWith(".docx")) {
            extracted = new Extracted(docx(file), "");
        } else if (type.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation") || path.endsWith(".pptx")) {
            extracted = new Extracted(pptx(file), "");
        } else if (type.equals(MediaTypes.XLSX) || path.endsWith(".xlsx") || path.endsWith(".xls")) {
            throw new ToolInputException(file.path() + " is a spreadsheet; use spreadsheet_read");
        } else if (type.startsWith("image/")) {
            throw new ToolInputException(file.path() + " is an image; use workspace_view to look at it");
        } else if (file.isText()) {
            extracted = new Extracted(file.text(), "");
        } else if (path.endsWith(".doc") || path.endsWith(".ppt")) {
            throw new ToolInputException(file.path() + " is a legacy Office format that can't be read; ask for it as .docx/.pptx or PDF");
        } else {
            throw new ToolInputException(file.path() + " (" + file.mediaType() + ") isn't a document type that can be read as text");
        }

        String text = extracted.text().strip();
        if (text.isEmpty()) {
            return extracted.header() + file.path() + " has no extractable text — it may be scanned or image-only. "
                    + "Use workspace_view to look at it directly.";
        }
        if (offset > text.length()) {
            throw new ToolInputException("offset " + offset + " is past the end of " + file.path() + " (" + text.length() + " characters)");
        }
        int end = (int) Math.min(text.length(), (long) offset + maxChars);
        String more = end < text.length()
                ? "\n\n[Showing characters " + offset + "-" + end + " of " + text.length() + "; call again with offset " + end + " for more.]"
                : "";
        return extracted.header() + text.substring(offset, end) + more;
    }

    private record Extracted(String text, String header) {
    }

    private static Extracted pdf(WorkspaceFile file, String pages) {
        try (PDDocument document = Loader.loadPDF(file.content())) {
            int total = document.getNumberOfPages();
            int first = 1;
            int last = total;
            if (pages != null && !pages.isBlank()) {
                String[] range = pages.strip().split("\\s*-\\s*");
                try {
                    first = Integer.parseInt(range[0]);
                    last = range.length > 1 ? Integer.parseInt(range[1]) : first;
                } catch (NumberFormatException e) {
                    throw new ToolInputException("'pages' must look like \"3\" or \"2-5\"");
                }
                if (range.length > 2 || first < 1 || last < first || first > total) {
                    throw new ToolInputException(file.path() + " has " + total + " page(s); 'pages' must be within 1-" + total);
                }
                last = Math.min(last, total);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(first);
            stripper.setEndPage(last);
            String header = file.path() + ": PDF, " + total + " page(s)"
                    + (first == 1 && last == total ? "" : ", showing pages " + first + "-" + last) + ".\n\n";
            return new Extracted(stripper.getText(document), header);
        } catch (IOException e) {
            throw new ToolInputException(file.path() + " isn't a readable PDF (it may be damaged or password-protected)");
        }
    }

    private static String docx(WorkspaceFile file) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(file.content()));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException e) {
            throw new ToolInputException(file.path() + " isn't a readable Word document");
        }
    }

    private static String pptx(WorkspaceFile file) {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(file.content()))) {
            StringBuilder out = new StringBuilder();
            List<XSLFSlide> slides = show.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                out.append("## Slide ").append(i + 1);
                if (slide.getTitle() != null && !slide.getTitle().isBlank()) {
                    out.append(": ").append(slide.getTitle().strip());
                }
                out.append('\n');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape text && !text.getText().isBlank()
                            && !text.getText().strip().equals(slide.getTitle() == null ? "" : slide.getTitle().strip())) {
                        out.append(text.getText().strip()).append('\n');
                    }
                }
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder noteText = new StringBuilder();
                    for (XSLFShape shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape text && !text.getText().isBlank()) {
                            noteText.append(text.getText().strip()).append(' ');
                        }
                    }
                    if (!noteText.isEmpty()) {
                        out.append("Notes: ").append(noteText.toString().strip()).append('\n');
                    }
                }
                out.append('\n');
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            throw new ToolInputException(file.path() + " isn't a readable PowerPoint file");
        }
    }
}
