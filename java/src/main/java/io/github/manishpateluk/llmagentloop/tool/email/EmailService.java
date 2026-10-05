package io.github.manishpateluk.llmagentloop.tool.email;

import io.github.manishpateluk.llmagentloop.tool.ToolContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An email account the agent can use — implement this over Gmail, Microsoft Graph, SMTP/IMAP,
 * or your own mail service, and register {@link EmailTools}. Use {@code context.scope()} to pick
 * the right user's account and credentials: one service instance serves every user.
 *
 * <p>Only {@link #send} is required. The others default to {@link UnsupportedOperationException},
 * which the tools report to the model as "not available" rather than failing the run, so a
 * send-only integration works fine. Calls block on the run's virtual thread.
 */
public interface EmailService {

    /** Sends {@code email} from the user's account; returns the provider's message id. */
    String send(Email email, ToolContext context);

    /** Saves {@code email} as a draft for the user to review and send themselves; returns the draft's id. */
    default String saveDraft(Email email, ToolContext context) {
        throw new UnsupportedOperationException("drafts");
    }

    /** Messages in the user's mailbox matching {@code query} (in the provider's own search syntax), newest first. */
    default List<EmailSummary> search(String query, int limit, ToolContext context) {
        throw new UnsupportedOperationException("searching");
    }

    /** One message in full, or empty if there's no such message. */
    default Optional<ReceivedEmail> read(String id, ToolContext context) {
        throw new UnsupportedOperationException("reading messages");
    }

    /**
     * An outgoing message.
     *
     * @param inReplyTo the id of the message this replies to, if any — implementations use it to thread the reply
     */
    record Email(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
                 List<Attachment> attachments, String inReplyTo) {
        public Email {
            to = List.copyOf(Objects.requireNonNull(to, "to"));
            cc = cc == null ? List.of() : List.copyOf(cc);
            bcc = bcc == null ? List.of() : List.copyOf(bcc);
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(body, "body");
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /** A file attached to an outgoing message, taken from the workspace. */
    record Attachment(String filename, String mediaType, byte[] data) {
        public Attachment {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(mediaType, "mediaType");
            data = Objects.requireNonNull(data, "data").clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /** A message as listed by {@link #search}. */
    record EmailSummary(String id, String from, String subject, Instant receivedAt, String snippet) {
    }

    /** A message as returned by {@link #read}. */
    record ReceivedEmail(String id, String from, List<String> to, List<String> cc, String subject, String body,
                         Instant receivedAt, List<String> attachmentNames) {
        public ReceivedEmail {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            attachmentNames = attachmentNames == null ? List.of() : List.copyOf(attachmentNames);
        }
    }
}
