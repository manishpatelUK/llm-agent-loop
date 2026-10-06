package io.github.manishpateluk.llmagentloop.tool.calendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.api.ApiAuth;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Event;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.NewEvent;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalDavCalendarTest {

    private record Seen(String method, String path, String depth, String auth, String contentType, String ifNoneMatch, String body) {
    }

    private static final String EVENTS = String.join("\r\n",
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
            "BEGIN:VEVENT", "UID:utc-1", "DTSTART:20261007T083000Z", "DTEND:20261007T090000Z", "SUMMARY:Standup", "END:VEVENT",
            "BEGIN:VEVENT", "UID:tz-2", "DTSTART;TZID=Europe/London:20261007T130000", "DTEND;TZID=Europe/London:20261007T150000",
            "SUMMARY:Board meeting\\, quarterly review with a title long enough that the server folds it onto",
            "  a continuation line", "LOCATION:Room 1\\; 2nd floor", "ATTENDEE;CN=CEO:mailto:ceo@acme.com",
            "DESCRIPTION:Line one\\nLine two", "END:VEVENT",
            "BEGIN:VEVENT", "UID:allday-3", "DTSTART;VALUE=DATE:20261008", "DTEND;VALUE=DATE:20261009", "SUMMARY:Offsite", "END:VEVENT",
            "BEGIN:VEVENT", "UID:dur-4", "DTSTART:20261007T160000Z", "DURATION:PT45M", "SUMMARY:Call",
            "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "END:VALARM", "END:VEVENT",
            "BEGIN:VEVENT", "UID:weekly-5", "RECURRENCE-ID:20261009T100000Z", "DTSTART:20261009T100000Z", "DTEND:20261009T103000Z",
            "SUMMARY:Weekly 1:1", "END:VEVENT",
            "END:VCALENDAR", "");

    private HttpServer server;
    private String base;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 207;
    private ToolContext context;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/calendars/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listsEventsWithACalendarQueryAndParsesICalendar() {
        List<Event> events = calendar().listEvents(Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), context);

        Seen report = seen.getFirst();
        assertThat(report.method()).isEqualTo("REPORT");
        assertThat(report.path()).isEqualTo("/calendars/alice/work/");
        assertThat(report.depth()).isEqualTo("1");
        assertThat(report.auth()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes()));
        assertThat(report.body()).contains("calendar-query", "<C:expand start=\"20261007T000000Z\" end=\"20261010T000000Z\"/>",
                "<C:time-range start=\"20261007T000000Z\"");

        assertThat(events).extracting(Event::id).containsExactly("utc-1", "tz-2", "dur-4", "allday-3", "weekly-5@20261009T100000Z");
        Event board = events.get(1);
        assertThat(board.start()).isEqualTo(Instant.parse("2026-10-07T12:00:00Z")); // 13:00 BST
        assertThat(board.title()).isEqualTo("Board meeting, quarterly review with a title long enough that the server folds it onto a continuation line");
        assertThat(board.location()).isEqualTo("Room 1; 2nd floor");
        assertThat(board.attendees()).containsExactly("ceo@acme.com");
        assertThat(board.description()).isEqualTo("Line one\nLine two");
        assertThat(events.get(2).end()).isEqualTo(Instant.parse("2026-10-07T16:45:00Z"));
        assertThat(events.get(2).description()).isNull(); // the VALARM's DESCRIPTION isn't the event's
        assertThat(events.get(3).start()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Test
    void createsAnEventAsAnIcsResource() {
        status = 201;
        NewEvent event = new NewEvent("Intro call; quick, friendly", Instant.parse("2026-10-08T09:00:00Z"),
                Instant.parse("2026-10-08T09:30:00Z"), "Europe/London", "Zoom", List.of("sam@globex.com"),
                "Agenda: " + "discuss the partnership ".repeat(6));

        Event created = calendar().createEvent(event, context);

        Seen put = seen.getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).startsWith("/calendars/alice/work/").endsWith(".ics");
        assertThat(put.contentType()).startsWith("text/calendar");
        assertThat(put.ifNoneMatch()).isEqualTo("*");
        assertThat(put.body()).contains("BEGIN:VEVENT\r\n", "DTSTART:20261008T090000Z\r\n", "DTEND:20261008T093000Z\r\n",
                "SUMMARY:Intro call\\; quick\\, friendly\r\n", "ORGANIZER:mailto:alice@acme.com\r\n",
                "DTSTAMP:20261006T120000Z\r\n");
        assertThat(put.body().lines()).allSatisfy(line -> assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
        assertThat(put.body()).contains("\r\n "); // the long DESCRIPTION was folded
        assertThat(ICalendar.parseEvents(put.body())).singleElement().satisfies(parsed -> {
            assertThat(parsed.title()).isEqualTo("Intro call; quick, friendly");
            assertThat(parsed.description()).isEqualTo(event.description());
            assertThat(parsed.attendees()).containsExactly("sam@globex.com"); // its ATTENDEE line is over 75 octets, so folded
        });
        assertThat(created.id()).endsWith("@llm-agent-loop");
    }

    @Test
    void worksThroughTheCalendarToolsEndToEnd() {
        String result = CalendarTools.findFreeTime(calendar()).handler().handle(new HashMap<>(Map.of(
                "from", "2026-10-07", "to", "2026-10-07", "timezone", "Europe/London", "duration_minutes", 60)), context);

        assertThat(result).contains("Wed 2026-10-07 10:00-13:00", "Wed 2026-10-07 15:00-17:00");
    }

    @Test
    void serverErrorsAreInfrastructureFailures() {
        status = 401;
        assertThatThrownBy(() -> calendar().listEvents(Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-08T00:00:00Z"), context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
    }

    @Test
    void responsesWithADoctypeAreRejected() {
        assertThatThrownBy(() -> CalDavCalendar.calendarData(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<D:multistatus xmlns:D=\"DAV:\">&e;</D:multistatus>").getBytes()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void busyTimesForOtherPeopleAreNotSupported() {
        assertThatThrownBy(() -> calendar().busyTimes(List.of("a@b.com"), Instant.now(), Instant.now(), context))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private CalDavCalendar calendar() {
        return CalDavCalendar.builder(scope -> URI.create(base + "/calendars/" + scope.userId() + "/work"))
                .auth(ApiAuth.basic(scope -> scope.userId(), scope -> "secret"))
                .organizer(scope -> scope.userId() + "@acme.com")
                .clock(Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Depth"), exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("If-None-Match"), body));
        byte[] response = status == 207
                ? ("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                + "<D:response><D:href>/calendars/alice/work/a.ics</D:href><D:propstat><D:prop><C:calendar-data>"
                + EVENTS.replace("&", "&amp;").replace("<", "&lt;")
                + "</C:calendar-data></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
                .getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
        if (response.length > 0) {
            exchange.getResponseBody().write(response);
        }
        exchange.close();
    }
}
