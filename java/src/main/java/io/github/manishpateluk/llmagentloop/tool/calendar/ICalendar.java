package io.github.manishpateluk.llmagentloop.tool.calendar;

import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.Event;
import io.github.manishpateluk.llmagentloop.tool.calendar.CalendarService.NewEvent;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slice of iCalendar (RFC 5545) that {@link CalDavCalendar} needs: reading VEVENTs (folded
 * lines, escaped text, UTC / TZID / floating / all-day times, DTEND or DURATION) and writing a
 * single VEVENT. Recurrence rules aren't interpreted — the server expands them.
 */
final class ICalendar {

    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern WEEKS = Pattern.compile("^([+-]?)P(\\d+)W$");

    private ICalendar() {
    }

    static String utc(Instant instant) {
        return UTC_STAMP.format(instant);
    }

    /** One iCalendar property line, parsed: {@code NAME;PARAM=V:value}. */
    private record Property(String name, Map<String, String> params, String value) {
    }

    /** Every VEVENT in {@code ics}. */
    static List<Event> parseEvents(String ics) {
        List<Event> events = new ArrayList<>();
        List<Property> current = null;
        int depth = 0; // nested components inside a VEVENT (e.g. VALARM) are skipped
        for (String line : unfold(ics)) {
            Property property = property(line);
            if (property == null) {
                continue;
            }
            if (property.name().equals("BEGIN")) {
                if (property.value().equalsIgnoreCase("VEVENT") && current == null) {
                    current = new ArrayList<>();
                } else if (current != null) {
                    depth++;
                }
            } else if (property.name().equals("END")) {
                if (current != null && depth > 0) {
                    depth--;
                } else if (current != null && property.value().equalsIgnoreCase("VEVENT")) {
                    Event event = toEvent(current);
                    if (event != null) {
                        events.add(event);
                    }
                    current = null;
                }
            } else if (current != null && depth == 0) {
                current.add(property);
            }
        }
        return events;
    }

    private static Event toEvent(List<Property> properties) {
        Map<String, Property> first = new HashMap<>();
        List<String> attendees = new ArrayList<>();
        for (Property p : properties) {
            first.putIfAbsent(p.name(), p);
            if (p.name().equals("ATTENDEE")) {
                attendees.add(stripMailto(p.value()));
            }
        }
        Property startProp = first.get("DTSTART");
        if (startProp == null) {
            return null;
        }
        Instant start = time(startProp);
        boolean allDay = isDate(startProp);
        Instant end;
        if (first.containsKey("DTEND")) {
            end = time(first.get("DTEND"));
        } else if (first.containsKey("DURATION")) {
            end = start.plus(duration(first.get("DURATION").value()));
        } else {
            end = allDay ? start.plus(Duration.ofDays(1)) : start;
        }
        String uid = first.containsKey("UID") ? first.get("UID").value() : null;
        if (uid != null && first.containsKey("RECURRENCE-ID")) {
            uid = uid + "@" + first.get("RECURRENCE-ID").value();
        }
        return new Event(uid, text(first.get("SUMMARY")), start, end, text(first.get("LOCATION")), attendees,
                text(first.get("DESCRIPTION")));
    }

    /** An .ics document holding one VEVENT. */
    static String write(String uid, NewEvent event, String organizer, Instant now) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//llm-agent-loop//CalDAV//EN");
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + uid);
        line(out, "DTSTAMP:" + utc(now));
        line(out, "DTSTART:" + utc(event.start()));
        line(out, "DTEND:" + utc(event.end()));
        line(out, "SUMMARY:" + escape(event.title()));
        if (event.location() != null && !event.location().isBlank()) {
            line(out, "LOCATION:" + escape(event.location()));
        }
        if (event.description() != null && !event.description().isBlank()) {
            line(out, "DESCRIPTION:" + escape(event.description()));
        }
        if (organizer != null && !organizer.isBlank()) {
            line(out, "ORGANIZER:mailto:" + organizer);
        }
        for (String attendee : event.attendees()) {
            line(out, "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + attendee);
        }
        line(out, "END:VEVENT");
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** Appends a content line, folded at 75 octets as RFC 5545 requires, with CRLF endings. */
    private static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }
        int limit = 75;
        StringBuilder chunk = new StringBuilder();
        int chunkBytes = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (chunkBytes + cpBytes > limit) {
                out.append(chunk).append("\r\n ");
                chunk.setLength(0);
                chunkBytes = 0;
                limit = 74; // continuation lines start with a space
            }
            chunk.appendCodePoint(cp);
            chunkBytes += cpBytes;
            i += Character.charCount(cp);
        }
        out.append(chunk).append("\r\n");
    }

    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        for (String raw : ics.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.getLast() + raw.substring(1));
            } else if (!raw.isEmpty()) {
                lines.add(raw);
            }
        }
        return lines;
    }

    /** Splits a content line at its first unquoted colon; parameters are {@code ;NAME=VALUE}. */
    private static Property property(String line) {
        boolean quoted = false;
        int colon = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ':' && !quoted) {
                colon = i;
                break;
            }
        }
        if (colon < 0) {
            return null;
        }
        String[] head = line.substring(0, colon).split(";");
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < head.length; i++) {
            int eq = head[i].indexOf('=');
            if (eq > 0) {
                params.put(head[i].substring(0, eq).toUpperCase(Locale.ROOT), head[i].substring(eq + 1).replace("\"", ""));
            }
        }
        return new Property(head[0].toUpperCase(Locale.ROOT), params, line.substring(colon + 1));
    }

    private static boolean isDate(Property p) {
        return "DATE".equalsIgnoreCase(p.params().get("VALUE")) || p.value().strip().length() == 8;
    }

    /** UTC ({@code ...Z}), {@code TZID}-local, floating (treated as UTC), or a date (midnight UTC). */
    private static Instant time(Property p) {
        String value = p.value().strip();
        try {
            if (isDate(p)) {
                return LocalDate.parse(value, DATE).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (value.endsWith("Z")) {
                return UTC_STAMP.parse(value, Instant::from);
            }
            LocalDateTime local = LocalDateTime.parse(value, LOCAL_STAMP);
            return local.atZone(zone(p.params().get("TZID"))).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Unreadable iCalendar time " + p.name() + ":" + value, e);
        }
    }

    private static ZoneId zone(String tzid) {
        if (tzid == null || tzid.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tzid.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC; // non-IANA ids (e.g. Windows names); servers expanding events send UTC anyway
        }
    }

    private static Duration duration(String value) {
        Matcher weeks = WEEKS.matcher(value.strip());
        if (weeks.matches()) {
            Duration d = Duration.ofDays(7L * Long.parseLong(weeks.group(2)));
            return weeks.group(1).equals("-") ? d.negated() : d;
        }
        try {
            return Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            return Duration.ZERO;
        }
    }

    private static String text(Property p) {
        if (p == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        String v = p.value();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char next = v.charAt(++i);
                out.append(next == 'n' || next == 'N' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n");
    }

    private static String stripMailto(String value) {
        return value.regionMatches(true, 0, "mailto:", 0, 7) ? value.substring(7) : value;
    }
}
