package io.github.manishpateluk.llmagentloop.tool.email;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.email.EmailService.Email;
import io.github.manishpateluk.llmagentloop.tool.email.EmailService.EmailSummary;
import io.github.manishpateluk.llmagentloop.tool.email.EmailService.ReceivedEmail;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailToolsTest {

    private final List<Email> sent = new CopyOnWriteArrayList<>();
    private final List<Email> drafts = new CopyOnWriteArrayList<>();
    private final List<Scope> scopes = new CopyOnWriteArrayList<>();
    private ToolContext context;

    /** A full fake account. */
    private final EmailService account = new EmailService() {
        @Override
        public String send(Email email, ToolContext context) {
            scopes.add(context.scope());
            sent.add(email);
            return "msg-" + sent.size();
        }

        @Override
        public String saveDraft(Email email, ToolContext context) {
            drafts.add(email);
            return "draft-1";
        }

        @Override
        public List<EmailSummary> search(String query, int limit, ToolContext context) {
            return List.of(new EmailSummary("m1", "sam@globex.com", "Invoice 42", Instant.parse("2026-10-01T09:00:00Z"),
                    "Please find   attached\nthe invoice"));
        }

        @Override
        public Optional<ReceivedEmail> read(String id, ToolContext context) {
            return id.equals("m1")
                    ? Optional.of(new ReceivedEmail("m1", "sam@globex.com", List.of("alice@acme.com"), List.of(),
                    "Invoice 42", "Hi Alice, invoice attached.", Instant.parse("2026-10-01T09:00:00Z"), List.of("invoice.pdf")))
                    : Optional.empty();
        }
    };

    /** Implements only the required method. */
    private final EmailService sendOnly = (email, context) -> "ok";

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void sendsWithRecipientsAndWorkspaceAttachmentsForTheRunsUser() {
        context.workspace().write("reports/q3.pdf", "%PDF".getBytes(), null);

        String result = EmailTools.send(account).handler().handle(new HashMap<>(Map.of(
                "to", List.of("sam@globex.com"), "cc", List.of(" bo@acme.com "), "subject", "Q3 report",
                "body", "Attached.", "attachments", List.of("reports/q3.pdf"), "in_reply_to", "m1")), context);

        assertThat(result).isEqualTo("Sent to sam@globex.com (message id msg-1).");
        Email email = sent.getFirst();
        assertThat(email.cc()).containsExactly("bo@acme.com");
        assertThat(email.inReplyTo()).isEqualTo("m1");
        assertThat(email.attachments()).singleElement().satisfies(a -> {
            assertThat(a.filename()).isEqualTo("q3.pdf");
            assertThat(a.mediaType()).isEqualTo("application/pdf");
        });
        assertThat(scopes).containsExactly(Scope.of("acme", "alice", "s1"));
    }

    @Test
    void draftsGoToTheDraftFolder() {
        EmailTools.draft(account).handler().handle(new HashMap<>(Map.of(
                "to", List.of("sam@globex.com"), "subject", "Hello", "body", "Draft body")), context);

        assertThat(drafts).hasSize(1);
        assertThat(sent).isEmpty();
    }

    @Test
    void searchAndReadFormatMessagesForTheModel() {
        assertThat(EmailTools.search(account).handler().handle(new HashMap<>(Map.of("query", "invoice")), context))
                .isEqualTo("- id m1 | 2026-10-01T09:00:00Z | from sam@globex.com | Invoice 42\n  Please find attached the invoice");
        assertThat(EmailTools.read(account).handler().handle(new HashMap<>(Map.of("id", "m1")), context))
                .contains("From: sam@globex.com", "Subject: Invoice 42", "Attachments: invoice.pdf", "Hi Alice, invoice attached.");
        assertThatThrownBy(() -> EmailTools.read(account).handler().handle(new HashMap<>(Map.of("id", "nope")), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("No message");
    }

    @Test
    void operationsTheServiceDoesNotSupportAreReportedNotFatal() {
        assertThatThrownBy(() -> EmailTools.draft(sendOnly).handler().handle(new HashMap<>(Map.of(
                "to", List.of("a@b.com"), "subject", "s", "body", "b")), context))
                .isInstanceOf(ToolInputException.class).hasMessage("Saving drafts isn't available with this email account");
        assertThatThrownBy(() -> EmailTools.search(sendOnly).handler().handle(new HashMap<>(Map.of("query", "x")), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Searching email isn't available");
    }

    @Test
    void badInputIsAFixableError() {
        assertThatThrownBy(() -> send(Map.of("to", List.of("not-an-address"), "subject", "s", "body", "b")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't a valid email address");
        assertThatThrownBy(() -> send(Map.of("to", List.of(), "subject", "s", "body", "b")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("at least one address");
        assertThatThrownBy(() -> send(Map.of("to", List.of("a@b.com"), "subject", "s", "body", "b",
                "attachments", List.of("missing.pdf"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("missing.pdf");
        assertThat(sent).isEmpty();
    }

    private String send(Map<String, Object> args) {
        return EmailTools.send(account).handler().handle(new HashMap<>(args), context);
    }

    @Test
    void recipientAndBodyLimitsProtectAgainstMassOrOversizedMail() {
        List<String> many = java.util.stream.IntStream.range(0, 30).mapToObj(i -> "p" + i + "@acme.com").toList();
        assertThatThrownBy(() -> EmailTools.send(account).handler().handle(new HashMap<>(Map.of(
                "to", many, "cc", many, "subject", "Hi", "body", "x")), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("At most 50 recipients");
        assertThatThrownBy(() -> EmailTools.send(account).handler().handle(new HashMap<>(Map.of(
                "to", List.of("sam@globex.com"), "subject", "Hi", "body", "x".repeat(EmailTools.MAX_BODY_CHARS + 1))), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("attach a document instead");
        assertThatThrownBy(() -> EmailTools.send(account).handler().handle(new HashMap<>(Map.of(
                "to", List.of(), "subject", "Hi", "body", "x")), context))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("at least one address");
        assertThat(sent).isEmpty();
    }
}
