package io.github.manishpateluk.llmagentloop.tool.calendar;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Busy;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Event;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.NewEvent;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarToolsTest {

    /** Wednesday 7 October 2026, London is on BST (UTC+1). */
    private final List<Event> events = new ArrayList<>(List.of(
            new Event("e1", "Standup", Instant.parse("2026-10-07T08:30:00Z"), Instant.parse("2026-10-07T09:00:00Z"),
                    null, List.of(), null),
            new Event("e2", "Board meeting", Instant.parse("2026-10-07T12:00:00Z"), Instant.parse("2026-10-07T14:00:00Z"),
                    "Room 1", List.of("ceo@acme.com"), null)));
    private final List<NewEvent> created = new CopyOnWriteArrayList<>();
    private ToolContext context;

    private final CalendarService calendar = new CalendarService() {
        @Override
        public List<Event> listEvents(Instant from, Instant to, ToolContext context) {
            return events.stream().filter(e -> e.end().isAfter(from) && e.start().isBefore(to)).toList();
        }

        @Override
        public Event createEvent(NewEvent event, ToolContext context) {
            created.add(event);
            return new Event("new-1", event.title(), event.start(), event.end(), event.location(), event.attendees(), null);
        }

        @Override
        public List<Busy> busyTimes(List<String> attendees, Instant from, Instant to, ToolContext context) {
            return List.of(new Busy("sam@globex.com", Instant.parse("2026-10-07T14:00:00Z"), Instant.parse("2026-10-07T15:30:00Z")));
        }
    };

    private final CalendarService readOnly = (from, to, context) -> List.of();

    @BeforeEach
    void setUp() {
        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @Test
    void listsEventsInTheRequestedTimezone() {
        String result = call(CalendarTools.list(calendar), Map.of("from", "2026-10-07", "to", "2026-10-07", "timezone", "Europe/London"));

        assertThat(result).isEqualTo("""
                Times in Europe/London:
                - Wed 2026-10-07 09:30-10:00 | Standup | id e1
                - Wed 2026-10-07 13:00-15:00 | Board meeting | Room 1 | with ceo@acme.com | id e2""");
    }

    @Test
    void createsEventsFromLocalTimes() {
        String result = call(CalendarTools.create(calendar), Map.of("title", "Intro call", "start", "2026-10-08T10:00",
                "end", "2026-10-08T10:30", "timezone", "Europe/London", "attendees", List.of("sam@globex.com")));

        assertThat(result).isEqualTo("Created \"Intro call\", Thu 2026-10-08 10:00-10:30 (Europe/London), "
                + "inviting sam@globex.com — id new-1.");
        assertThat(created.getFirst().start()).isEqualTo(Instant.parse("2026-10-08T09:00:00Z"));
        assertThat(created.getFirst().timeZone()).isEqualTo("Europe/London");
    }

    @Test
    void findsFreeTimeAroundTheUsersEventsWithinWorkingHours() {
        String result = call(CalendarTools.findFreeTime(calendar), Map.of("from", "2026-10-07", "to", "2026-10-07",
                "timezone", "Europe/London", "duration_minutes", 60));

        assertThat(result).isEqualTo("""
                Free slots of at least 60 minutes (Europe/London):
                - Wed 2026-10-07 10:00-13:00
                - Wed 2026-10-07 15:00-17:00""");
    }

    @Test
    void findsTimeWhenAttendeesAreFreeToo() {
        String result = call(CalendarTools.findFreeTime(calendar), Map.of("from", "2026-10-07", "to", "2026-10-07",
                "timezone", "Europe/London", "duration_minutes", 60, "attendees", List.of("sam@globex.com")));

        assertThat(result).contains("for you and sam@globex.com", "10:00-13:00").doesNotContain("15:00-17:00");
    }

    @Test
    void weekendsAreSkippedUnlessIncluded() {
        Map<String, Object> saturday = new HashMap<>(Map.of("from", "2026-10-10", "to", "2026-10-10",
                "timezone", "Europe/London", "duration_minutes", 30));

        assertThat(call(CalendarTools.findFreeTime(calendar), saturday)).startsWith("No free");
        saturday.put("include_weekends", true);
        saturday.put("working_hours", "10:00-12:00");
        assertThat(call(CalendarTools.findFreeTime(calendar), saturday)).contains("Sat 2026-10-10 10:00-12:00");
    }

    @Test
    void unsupportedOperationsAndBadInputAreFixableErrors() {
        assertThatThrownBy(() -> call(CalendarTools.create(readOnly), Map.of("title", "x", "start", "2026-10-08T10:00",
                "end", "2026-10-08T11:00", "timezone", "UTC")))
                .isInstanceOf(ToolInputException.class).hasMessage("Creating calendar events isn't available with this calendar");
        assertThatThrownBy(() -> call(CalendarTools.findFreeTime(readOnly), Map.of("from", "2026-10-07", "to", "2026-10-07",
                "timezone", "UTC", "duration_minutes", 30, "attendees", List.of("a@b.com"))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("other people's availability");
        assertThatThrownBy(() -> call(CalendarTools.list(calendar), Map.of("from", "tomorrow", "to", "2026-10-07", "timezone", "UTC")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("must look like");
        assertThatThrownBy(() -> call(CalendarTools.list(calendar), Map.of("from", "2026-10-07", "to", "2026-10-07", "timezone", "Mars/Base")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Unknown timezone");
        assertThatThrownBy(() -> call(CalendarTools.create(calendar), Map.of("title", "x", "start", "2026-10-08T11:00",
                "end", "2026-10-08T10:00", "timezone", "UTC")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("after 'start'");
        assertThatThrownBy(() -> call(CalendarTools.list(calendar), Map.of("from", "2026-01-01", "to", "2026-12-31", "timezone", "UTC")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("62 days");
    }

    private String call(io.github.manishpateluk.llmagentloop.tool.RegisteredTool tool, Map<String, Object> args) {
        return tool.handler().handle(new HashMap<>(args), context);
    }
}
