package io.github.manishpateluk.llmagentloop.tool.calendar;

import io.github.manishpateluk.llmagentloop.tool.ToolContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A calendar the agent can use — implement this over Google Calendar, Microsoft Graph, CalDAV or
 * your own scheduling system, and register {@link CalendarTools}. Use {@code context.scope()} to
 * pick the right user's calendar: one service instance serves every user.
 *
 * <p>{@link #listEvents} is required; free-time search is built on it. {@link #createEvent} and
 * {@link #busyTimes} (other people's availability) default to {@link UnsupportedOperationException},
 * which the tools report to the model as "not available". Calls block on the run's virtual thread.
 */
public interface CalendarService {

    /** The user's events overlapping {@code [from, to)}, in start order. */
    List<Event> listEvents(Instant from, Instant to, ToolContext context);

    /** Creates an event on the user's calendar (inviting any attendees); returns it as created, with its id. */
    default Event createEvent(NewEvent event, ToolContext context) {
        throw new UnsupportedOperationException("creating events");
    }

    /** When each of {@code attendees} is busy within {@code [from, to)} — for finding a time that suits everyone. */
    default List<Busy> busyTimes(List<String> attendees, Instant from, Instant to, ToolContext context) {
        throw new UnsupportedOperationException("checking other people's availability");
    }

    record Event(String id, String title, Instant start, Instant end, String location, List<String> attendees,
                 String description) {
        public Event {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            attendees = attendees == null ? List.of() : List.copyOf(attendees);
        }
    }

    /**
     * @param timeZone IANA zone the event was described in (e.g. for how invitations display it)
     */
    record NewEvent(String title, Instant start, Instant end, String timeZone, String location, List<String> attendees,
                    String description) {
        public NewEvent {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            attendees = attendees == null ? List.of() : List.copyOf(attendees);
        }
    }

    /** One busy interval for {@code attendee}. */
    record Busy(String attendee, Instant start, Instant end) {
    }
}
