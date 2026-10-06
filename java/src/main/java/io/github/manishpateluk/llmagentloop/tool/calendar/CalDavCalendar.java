package io.github.manishpateluk.llmagentloop.tool.calendar;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.api.ApiAuth;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * A {@link CalendarService} over any CalDAV server (RFC 4791) — iCloud, Fastmail, Nextcloud,
 * Radicale, Baikal, Zimbra and others. Each user's calendar collection URL and credentials are
 * chosen per run from its {@link Scope}:
 *
 * <pre>{@code
 * CalendarService calendar = CalDavCalendar.builder(
 *                 scope -> URI.create("https://caldav.example.com/calendars/" + scope.userId() + "/work/"))
 *         .auth(ApiAuth.basic(scope -> users.caldavLogin(scope), scope -> users.caldavPassword(scope)))
 *         .organizer(scope -> users.email(scope))     // optional: lets the server send invitations
 *         .build();
 * registry.registerAll(CalendarTools.all(calendar));
 * }</pre>
 *
 * <p>Listing uses a {@code calendar-query} REPORT that asks the server to expand recurring events
 * into instances, so recurrence rules are the server's job. Creating writes a new {@code .ics}
 * resource with {@code PUT}; servers that implement CalDAV scheduling then email any attendees.
 * Other people's availability ({@code busyTimes}) isn't supported — free-time search covers the
 * user's own calendar. An unreachable server, or one that rejects the credentials, ends the run.
 */
public final class CalDavCalendar implements CalendarService {

    private final Function<Scope, URI> calendarUrl;
    private final ApiAuth auth;
    private final Function<Scope, String> organizer;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private CalDavCalendar(Builder builder) {
        this.calendarUrl = builder.calendarUrl;
        this.auth = builder.auth;
        this.organizer = builder.organizer;
        this.timeout = builder.timeout;
        this.clock = builder.clock;
    }

    /** {@code calendarUrl}: the user's calendar collection, e.g. {@code https://host/calendars/alice/work/}. */
    public static Builder builder(Function<Scope, URI> calendarUrl) {
        return new Builder(calendarUrl);
    }

    @Override
    public List<Event> listEvents(Instant from, Instant to, ToolContext context) {
        String range = "start=\"" + ICalendar.utc(from) + "\" end=\"" + ICalendar.utc(to) + "\"";
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop>
                    <C:calendar-data><C:expand %s/></C:calendar-data>
                  </D:prop>
                  <C:filter>
                    <C:comp-filter name="VCALENDAR">
                      <C:comp-filter name="VEVENT"><C:time-range %s/></C:comp-filter>
                    </C:comp-filter>
                  </C:filter>
                </C:calendar-query>
                """.formatted(range, range);
        HttpResponse<byte[]> response = send(context.scope(), request(context.scope(), collection(context.scope()))
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("REPORT", HttpRequest.BodyPublishers.ofString(body)));
        if (response.statusCode() != 207 && response.statusCode() / 100 != 2) {
            throw new IllegalStateException("CalDAV server returned HTTP " + response.statusCode() + " listing events");
        }
        List<Event> events = new ArrayList<>();
        for (String ics : calendarData(response.body())) {
            for (Event event : ICalendar.parseEvents(ics)) {
                if (event.end().isAfter(from) && event.start().isBefore(to)) {
                    events.add(event);
                }
            }
        }
        events.sort(Comparator.comparing(Event::start));
        return events;
    }

    @Override
    public Event createEvent(NewEvent event, ToolContext context) {
        String uid = UUID.randomUUID() + "@llm-agent-loop";
        String ics = ICalendar.write(uid, event, organizer == null ? null : organizer.apply(context.scope()), clock.instant());
        URI resource = collection(context.scope()).resolve(uid.replace("@", "-") + ".ics");
        HttpResponse<byte[]> response = send(context.scope(), request(context.scope(), resource)
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofString(ics)));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("CalDAV server returned HTTP " + response.statusCode() + " creating an event");
        }
        return new Event(uid, event.title(), event.start(), event.end(), event.location(), event.attendees(), event.description());
    }

    /** The collection URL, with a trailing slash so event resources resolve inside it. */
    private URI collection(Scope scope) {
        URI url = Objects.requireNonNull(calendarUrl.apply(scope), "calendarUrl returned null");
        String text = url.toString();
        return text.endsWith("/") ? url : URI.create(text + "/");
    }

    private HttpRequest.Builder request(Scope scope, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        auth.headers(scope).forEach(builder::header);
        return builder;
    }

    private HttpResponse<byte[]> send(Scope scope, HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("CalDAV request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the CalDAV server", e);
        }
    }

    /** The text of every {@code calendar-data} element in a WebDAV multistatus response. */
    static List<String> calendarData(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Server responses are untrusted: no DTDs, external entities or XInclude.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList nodes = document.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data");
            List<String> data = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = ((Element) nodes.item(i)).getTextContent();
                if (text != null && !text.isBlank()) {
                    data.add(text);
                }
            }
            return data;
        } catch (Exception e) {
            throw new IllegalStateException("CalDAV server returned a response that isn't valid WebDAV XML: "
                    + new String(xml, 0, Math.min(xml.length, 200), StandardCharsets.UTF_8), e);
        }
    }

    public static final class Builder {

        private final Function<Scope, URI> calendarUrl;
        private ApiAuth auth = ApiAuth.NONE;
        private Function<Scope, String> organizer;
        private Duration timeout = Duration.ofSeconds(30);
        private Clock clock = Clock.systemUTC();

        private Builder(Function<Scope, URI> calendarUrl) {
            this.calendarUrl = Objects.requireNonNull(calendarUrl, "calendarUrl");
        }

        /** How to authenticate, per scope — usually {@code ApiAuth.basic(...)} or {@code ApiAuth.bearer(...)}. */
        public Builder auth(ApiAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        /** The user's own email address, set as the event's organizer so scheduling-capable servers send invitations. */
        public Builder organizer(Function<Scope, String> organizer) {
            this.organizer = organizer;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** For tests: the clock used for {@code DTSTAMP}. */
        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public CalDavCalendar build() {
            return new CalDavCalendar(this);
        }
    }
}
