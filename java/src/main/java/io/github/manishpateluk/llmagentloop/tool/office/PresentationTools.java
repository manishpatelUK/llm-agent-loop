package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code presentation_create}: a PowerPoint deck (.pptx) in the workspace from a list of slides —
 * a title slide and title-plus-bullets slides, with speaker notes. Read decks back with
 * {@code document_read}. Register with {@code registry.registerAll(PresentationTools.all())};
 * needs a workspace configured.
 */
public final class PresentationTools {

    public static final String CREATE = "presentation_create";

    static final int MAX_SLIDES = 200;
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private PresentationTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(create());
    }

    public static RegisteredTool create() {
        Map<String, Object> slide = ToolSchemas.object(List.of("title"),
                "layout", ToolSchemas.stringEnum("\"title\" for a title slide (title + subtitle), \"content\" for title + bullets. "
                        + "Defaults to \"content\".", List.of("title", "content")),
                "title", ToolSchemas.string("The slide's title."),
                "subtitle", ToolSchemas.string("Title slides only: the subtitle."),
                "bullets", ToolSchemas.stringArray("Content slides: bullet points, top to bottom. Start a bullet with two "
                        + "spaces per level to nest it under the one before."),
                "notes", ToolSchemas.string("Speaker notes."));
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CREATE)
                        .description("Create a PowerPoint presentation (.pptx) in the workspace, or replace one, from a "
                                + "list of slides. Keep slides short: a clear title and a few concise bullets, with detail "
                                + "in the speaker notes.")
                        .parameters(ToolSchemas.object(List.of("path", "slides"),
                                "path", ToolSchemas.string("Workspace path ending in .pptx, e.g. \"pitch/seed-round.pptx\"."),
                                "slides", ToolSchemas.array("The slides, in order.", slide)))
                        .build(),
                PresentationTools::create);
    }

    private static String create(Map<String, Object> args, ToolContext context) {
        String path = ToolArguments.requireString(args, "path");
        if (!path.toLowerCase(Locale.ROOT).endsWith(".pptx")) {
            throw new ToolInputException("'path' must end in .pptx, was \"" + path + "\"");
        }
        Object value = args.get("slides");
        if (!(value instanceof List<?> slides) || slides.isEmpty() || !slides.stream().allMatch(Map.class::isInstance)) {
            throw new ToolInputException("'slides' must be a non-empty list of slide objects");
        }
        if (slides.size() > MAX_SLIDES) {
            throw new ToolInputException("At most " + MAX_SLIDES + " slides per presentation");
        }
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlideMaster master = show.getSlideMasters().getFirst();
            int number = 0;
            for (Object item : slides) {
                number++;
                @SuppressWarnings("unchecked")
                Map<String, Object> spec = (Map<String, Object>) item;
                addSlide(show, master, spec, number);
            }
            show.write(out);
            WorkspaceFile file = context.workspace().write(path, out.toByteArray(), PPTX);
            return "Created " + file.path() + " (" + slides.size() + " slides, " + file.size() + " bytes).";
        } catch (WorkspaceException e) {
            throw new ToolInputException(e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addSlide(XMLSlideShow show, XSLFSlideMaster master, Map<String, Object> spec, int number) {
        String layoutName = ToolArguments.optionalString(spec, "layout");
        boolean titleSlide = "title".equalsIgnoreCase(layoutName);
        if (layoutName != null && !titleSlide && !"content".equalsIgnoreCase(layoutName)) {
            throw new ToolInputException("Slide " + number + ": layout must be \"title\" or \"content\"");
        }
        String title;
        try {
            title = ToolArguments.requireString(spec, "title");
        } catch (ToolInputException e) {
            throw new ToolInputException("Slide " + number + ": " + e.getMessage());
        }
        XSLFSlideLayout layout = master.getLayout(titleSlide ? SlideLayout.TITLE : SlideLayout.TITLE_AND_CONTENT);
        XSLFSlide slide = show.createSlide(layout);
        slide.getPlaceholder(0).setText(title);

        XSLFTextShape body = slide.getPlaceholder(1);
        if (titleSlide) {
            String subtitle = ToolArguments.optionalString(spec, "subtitle");
            if (body != null) {
                body.setText(subtitle == null ? "" : subtitle);
            }
        } else if (body != null) {
            body.clearText();
            for (String bullet : ToolArguments.optionalStringList(spec, "bullets")) {
                int indent = 0;
                while (indent < bullet.length() && bullet.charAt(indent) == ' ') {
                    indent++;
                }
                XSLFTextParagraph paragraph = body.addNewTextParagraph();
                paragraph.setIndentLevel(Math.min(indent / 2, 4));
                paragraph.addNewTextRun().setText(bullet.strip());
            }
        }

        String notes = ToolArguments.optionalString(spec, "notes");
        if (notes != null && !notes.isBlank()) {
            XSLFTextShape placeholder = show.getNotesSlide(slide).getPlaceholder(1);
            if (placeholder != null) {
                placeholder.setText(notes);
            }
        }
    }
}
