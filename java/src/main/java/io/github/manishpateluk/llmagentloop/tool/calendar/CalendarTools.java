package io.github.manishpateluk.llmagentloop.tool.calendar;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Busy;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Event;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.NewEvent;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Calendar tools over a {@link CalendarService}: {@code calendar_list_events},
 * {@code calendar_create_event} and {@code calendar_find_free_time}. Times are exchanged with the
 * model as local date-times in a named timezone ({@code 2026-10-07T14:30} in
 * {@code Europe/London}), which models handle far more reliably than raw instants. Register with
 * {@code registry.registerAll(CalendarTools.all(service))}.
 */
public final class CalendarTools {

    public static final String LIST = "calendar_list_events";
    public static final String CREATE = "calendar_create_event";
    public static final String FIND_FREE = "calendar_find_free_time";

    static final Duration MAX_RANGE = Duration.ofDays(62);
    static final int MAX_SLOTS = 10;
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s<>,;]+@[^@\\s<>,;]+\\.[^@\\s<>,;]+$");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private CalendarTools() {
    }

    public static List<RegisteredTool> all(CalendarService service) {
        return List.of(list(service), create(service), findFreeTime(service));
    }

    public static RegisteredTool list(CalendarService service) {
        Objects.requireNonNull(service, "service");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(LIST)
                        .description("List the user's calendar events between two times.")
                        .parameters(ToolSchemas.object(List.of("from", "to", "timezone"),
                                "from", ToolSchemas.string("Start, as a local date-time \"YYYY-MM-DDTHH:MM\" or a date \"YYYY-MM-DD\"."),
                                "to", ToolSchemas.string("End, in the same form (a date means the end of that day)."),
                                "timezone", ToolSchemas.string("IANA timezone the times are in, e.g. \"Europe/London\".")))
                        .build(),
                (args, context) -> {
                    ZoneId zone = zone(args.get("timezone"));
                    Instant from = time(args, "from", zone, false);
                    Instant to = time(args, "to", zone, true);
                    checkRange(from, to);
                    List<Event> events = new ArrayList<>(service.listEvents(from, to, context));
                    if (events.isEmpty()) {
                        return "No events.";
                    }
                    events.sort(Comparator.comparing(Event::start));
                    StringBuilder out = new StringBuilder("Times in " + zone.getId() + ":\n");
                    for (Event event : events) {
                        out.append("- ").append(describe(event.start(), event.end(), zone)).append(" | ")
                                .append(event.title() == null ? "(no title)" : event.title());
                        if (event.location() != null && !event.location().isBlank()) {
                            out.append(" | ").append(event.location());
                        }
                        if (!event.attendees().isEmpty()) {
                            out.append(" | with ").append(String.join(", ", event.attendees()));
                        }
                        if (event.id() != null) {
                            out.append(" | id ").append(event.id());
                        }
                        out.append('\n');
                    }
                    return out.toString().strip();
                }).withUntrustedOutput();
    }

    public static RegisteredTool create(CalendarService service) {
        Objects.requireNonNull(service, "service");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CREATE)
                        .description("Create an event on the user's calendar, inviting any attendees. Check for clashes "
                                + "with " + FIND_FREE + " or " + LIST + " first.")
                        .parameters(ToolSchemas.object(List.of("title", "start", "end", "timezone"),
                                "title", ToolSchemas.string("Event title."),
                                "start", ToolSchemas.string("Start, as a local date-time \"YYYY-MM-DDTHH:MM\"."),
                                "end", ToolSchemas.string("End, as a local date-time \"YYYY-MM-DDTHH:MM\"."),
                                "timezone", ToolSchemas.string("IANA timezone the times are in, e.g. \"Europe/London\"."),
                                "attendees", ToolSchemas.stringArray("Email addresses to invite."),
                                "location", ToolSchemas.string("Where, or a video-call link."),
                                "description", ToolSchemas.string("Agenda or notes.")))
                        .build(),
                (args, context) -> {
                    ZoneId zone = zone(args.get("timezone"));
                    Instant start = time(args, "start", zone, false);
                    Instant end = time(args, "end", zone, false);
                    if (!end.isAfter(start)) {
                        throw new ToolInputException("'end' must be after 'start'");
                    }
                    if (Duration.between(start, end).compareTo(Duration.ofDays(14)) > 0) {
                        throw new ToolInputException("An event can't be longer than 14 days");
                    }
                    List<String> attendees = addresses(ToolArguments.optionalStringList(args, "attendees"));
                    NewEvent event = new NewEvent(ToolArguments.requireString(args, "title"), start, end, zone.getId(),
                            ToolArguments.optionalString(args, "location"), attendees, ToolArguments.optionalString(args, "description"));
                    Event created = unsupportedAsError(() -> service.createEvent(event, context), "creating calendar events");
                    return "Created \"" + event.title() + "\", " + describe(start, end, zone) + " (" + zone.getId() + ")"
                            + (attendees.isEmpty() ? "" : ", inviting " + String.join(", ", attendees))
                            + (created != null && created.id() != null ? " — id " + created.id() : "") + ".";
                });
    }

    public static RegisteredTool findFreeTime(CalendarService service) {
        Objects.requireNonNull(service, "service");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(FIND_FREE)
                        .description("Find free slots of a given length in the user's calendar — and optionally other "
                                + "attendees' — within working hours on weekdays.")
                        .parameters(ToolSchemas.object(List.of("from", "to", "timezone", "duration_minutes"),
                                "from", ToolSchemas.string("Search from, as \"YYYY-MM-DD\" or \"YYYY-MM-DDTHH:MM\"."),
                                "to", ToolSchemas.string("Search until, in the same form (a date means the end of that day)."),
                                "timezone", ToolSchemas.string("IANA timezone, e.g. \"Europe/London\"; working hours are in this zone."),
                                "duration_minutes", ToolSchemas.integer("How long the slot must be, in minutes."),
                                "attendees", ToolSchemas.stringArray("Other people's email addresses who must also be free."),
                                "working_hours", ToolSchemas.string("Working hours as \"HH:MM-HH:MM\". Defaults to \"09:00-17:00\"."),
                                "include_weekends", ToolSchemas.bool("Also search Saturdays and Sundays. Defaults to false.")))
                        .build(),
                (args, context) -> {
                    ZoneId zone = zone(args.get("timezone"));
                    Instant from = time(args, "from", zone, false);
                    Instant to = time(args, "to", zone, true);
                    checkRange(from, to);
                    Duration length = Duration.ofMinutes(ToolArguments.optionalInt(args, "duration_minutes", 30, 5, 24 * 60));
                    LocalTime[] hours = workingHours(ToolArguments.optionalString(args, "working_hours"));
                    boolean weekends = ToolArguments.optionalBoolean(args, "include_weekends", false);
                    List<String> attendees = addresses(ToolArguments.optionalStringList(args, "attendees"));

                    List<Instant[]> busy = new ArrayList<>();
                    for (Event event : service.listEvents(from, to, context)) {
                        busy.add(new Instant[]{event.start(), event.end()});
                    }
                    if (!attendees.isEmpty()) {
                        List<Busy> others = unsupportedAsError(() -> service.busyTimes(attendees, from, to, context),
                                "checking other people's availability");
                        for (Busy b : others) {
                            busy.add(new Instant[]{b.start(), b.end()});
                        }
                    }
                    List<Instant[]> slots = freeSlots(from, to, zone, hours, weekends, length, busy);
                    if (slots.isEmpty()) {
                        return "No free " + length.toMinutes() + "-minute slots in that range.";
                    }
                    StringBuilder out = new StringBuilder("Free slots of at least " + length.toMinutes() + " minutes ("
                            + zone.getId() + (attendees.isEmpty() ? "" : ", for you and " + String.join(", ", attendees)) + "):\n");
                    for (Instant[] slot : slots) {
                        out.append("- ").append(describe(slot[0], slot[1], zone)).append('\n');
                    }
                    return out.toString().strip();
                });
    }

    /** Free windows within working hours, minus busy intervals, at least {@code length} long; the first {@link #MAX_SLOTS}. */
    static List<Instant[]> freeSlots(Instant from, Instant to, ZoneId zone, LocalTime[] hours, boolean weekends,
                                     Duration length, List<Instant[]> busy) {
        List<Instant[]> sortedBusy = new ArrayList<>(busy);
        sortedBusy.sort(Comparator.comparing(b -> b[0]));
        List<Instant[]> slots = new ArrayList<>();
        LocalDate day = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        for (; !day.isAfter(last) && slots.size() < MAX_SLOTS; day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (!weekends && (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)) {
                continue;
            }
            Instant windowStart = max(day.atTime(hours[0]).atZone(zone).toInstant(), from);
            Instant windowEnd = min(day.atTime(hours[1]).atZone(zone).toInstant(), to);
            Instant cursor = windowStart;
            for (Instant[] b : sortedBusy) {
                if (!b[1].isAfter(cursor) || !b[0].isBefore(windowEnd)) {
                    continue;
                }
                if (Duration.between(cursor, b[0]).compareTo(length) >= 0) {
                    slots.add(new Instant[]{cursor, b[0]});
                }
                cursor = max(cursor, b[1]);
            }
            if (cursor.isBefore(windowEnd) && Duration.between(cursor, windowEnd).compareTo(length) >= 0) {
                slots.add(new Instant[]{cursor, windowEnd});
            }
        }
        return slots.size() > MAX_SLOTS ? slots.subList(0, MAX_SLOTS) : slots;
    }

    private static ZoneId zone(Object value) {
        if (!(value instanceof String id) || id.isBlank()) {
            throw new ToolInputException("'timezone' is required, e.g. \"Europe/London\"");
        }
        try {
            return ZoneId.of(id.strip());
        } catch (DateTimeException e) {
            throw new ToolInputException("Unknown timezone \"" + id + "\"; use an IANA name like \"Europe/London\"");
        }
    }

    /** A local date-time (or date: start of day, or end of day if {@code endOfDay}) in {@code zone}; an explicit offset is honoured. */
    private static Instant time(java.util.Map<String, Object> args, String name, ZoneId zone, boolean endOfDay) {
        String text = ToolArguments.requireString(args, name).strip();
        try {
            if (text.length() == 10) {
                LocalDate date = LocalDate.parse(text);
                return (endOfDay ? date.plusDays(1).atStartOfDay() : date.atStartOfDay()).atZone(zone).toInstant();
            }
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException notOffset) {
                return LocalDateTime.parse(text).atZone(zone).toInstant();
            }
        } catch (DateTimeParseException e) {
            throw new ToolInputException("'" + name + "' must look like \"2026-10-07T14:30\" or \"2026-10-07\", was \"" + text + "\"");
        }
    }

    private static void checkRange(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new ToolInputException("'to' must be after 'from'");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new ToolInputException("The range can't be longer than " + MAX_RANGE.toDays() + " days");
        }
    }

    private static LocalTime[] workingHours(String text) {
        if (text == null || text.isBlank()) {
            return new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(17, 0)};
        }
        String[] parts = text.strip().split("\\s*-\\s*");
        try {
            LocalTime start = LocalTime.parse(parts[0]);
            LocalTime end = LocalTime.parse(parts[1]);
            if (parts.length != 2 || !end.isAfter(start)) {
                throw new DateTimeException("order");
            }
            return new LocalTime[]{start, end};
        } catch (RuntimeException e) {
            throw new ToolInputException("'working_hours' must look like \"09:00-17:00\"");
        }
    }

    private static List<String> addresses(List<String> values) {
        List<String> addresses = new ArrayList<>();
        for (String value : values) {
            if (!ADDRESS.matcher(value.strip()).matches()) {
                throw new ToolInputException("'" + value + "' isn't a valid email address");
            }
            addresses.add(value.strip());
        }
        return addresses;
    }

    private static String describe(Instant start, Instant end, ZoneId zone) {
        ZonedDateTime s = start.atZone(zone);
        ZonedDateTime e = end.atZone(zone);
        String day = s.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String endText = s.toLocalDate().equals(e.toLocalDate()) ? e.toLocalTime().toString() : e.format(LOCAL);
        return day + " " + s.format(LOCAL) + "-" + endText;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static <T> T unsupportedAsError(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (UnsupportedOperationException e) {
            throw new ToolInputException(Character.toUpperCase(what.charAt(0)) + what.substring(1)
                    + " isn't available with this calendar");
        }
    }
}
