package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A file the user supplied with a request — an upload in a chat, an email attachment, anything
 * that arrives as bytes: images, PDFs, spreadsheets, CSVs, documents. See
 * {@link LoopRequest#attachments()} for how each kind reaches the model.
 *
 * @param filename  the file's name as the user knows it, e.g. {@code "Q3 results.pdf"}
 * @param mediaType e.g. {@code image/png}; {@link #of(String, byte[])} guesses it from the name
 * @param data      the bytes, defensively copied
 */
public record InputFile(String filename, String mediaType, byte[] data) {

    public InputFile {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        Objects.requireNonNull(mediaType, "mediaType");
        data = Objects.requireNonNull(data, "data").clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    /** Media type guessed from {@code filename}'s extension. */
    public static InputFile of(String filename, byte[] data) {
        return new InputFile(filename, MediaTypes.guess(filename, MediaTypes.OCTET_STREAM), data);
    }

    public static InputFile of(String filename, String mediaType, byte[] data) {
        return new InputFile(filename, mediaType, data);
    }

    /** Reads {@code stream} fully (it isn't closed); media type guessed from {@code filename}. */
    public static InputFile of(String filename, InputStream stream) {
        return of(filename, MediaTypes.guess(filename, MediaTypes.OCTET_STREAM), stream);
    }

    /** Reads {@code stream} fully (it isn't closed). */
    public static InputFile of(String filename, String mediaType, InputStream stream) {
        try {
            return new InputFile(filename, mediaType, stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + filename, e);
        }
    }

    /** A file on local disk — for tools and tests; a server usually receives uploads as streams instead. */
    public static InputFile of(Path path) {
        try {
            String name = path.getFileName().toString();
            return new InputFile(name, MediaTypes.guess(name, MediaTypes.OCTET_STREAM), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }
}
