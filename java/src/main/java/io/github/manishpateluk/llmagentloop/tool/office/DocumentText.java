package io.github.manishpateluk.llmagentloop.tool.office;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;

import java.util.Locale;
import java.util.Optional;

/**
 * Plain text from a workspace file, for indexing and search: text files as they are, and the text
 * of PDF, Word (.docx) and PowerPoint (.pptx) documents — the same extraction {@code document_read}
 * uses. Empty for files with no extractable text (images, scanned PDFs, spreadsheets, other binaries)
 * or that can't be read.
 */
public final class DocumentText {

    private DocumentText() {
    }

    public static Optional<String> extract(WorkspaceFile file) {
        String type = file.mediaType().toLowerCase(Locale.ROOT);
        String path = file.path().toLowerCase(Locale.ROOT);
        try {
            String text;
            if (type.equals("application/pdf") || path.endsWith(".pdf")) {
                text = DocumentTools.pdf(file, null).text();
            } else if (type.equals(MediaTypes.DOCX) || path.endsWith(".docx")) {
                text = DocumentTools.docx(file);
            } else if (type.equals(PresentationTools.PPTX) || path.endsWith(".pptx")) {
                text = DocumentTools.pptx(file);
            } else if (file.isText()) {
                text = file.text();
            } else {
                return Optional.empty();
            }
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (ToolInputException e) {
            return Optional.empty(); // unreadable or damaged: nothing to index
        }
    }
}
