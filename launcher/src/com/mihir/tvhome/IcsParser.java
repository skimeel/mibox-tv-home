package com.mihir.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small iCalendar (RFC 5545) reader for the schedule card. It reads VEVENTs only and handles
 * RRULE (DAILY, WEEKLY with BYDAY, MONTHLY, YEARLY, INTERVAL, COUNT, UNTIL, plus BYMONTHDAY,
 * BYMONTH and BYDAY ordinals such as 2TU), EXDATE, RECURRENCE-ID overrides, STATUS:CANCELLED,
 * TZID, UTC and floating times, and all-day dates. Recurrences are expanded only inside the
 * requested window and the feed is read line by line, so memory stays small however much history
 * the calendar has. Plain Java with no Android classes, so it can be tested on a desktop JVM.
 */
final class IcsParser {

    private IcsParser() { }

    /** One occurrence in the window. All-day events run from local midnight to local midnight, end exclusive. */
    static final class Event {
        final long start, end;
        final boolean allDay;
        final String title;

        Event(long start, long end, boolean allDay, String title) {
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.title = title;
        }
    }

    /** The response wasn't an iCalendar feed (for example an error or sign-in page). */
    static final class NotACalendar extends IOException {
        NotACalendar() { super("not an iCalendar feed"); }
    }

    // Guards against runaway feeds: total characters read, and recurrence periods walked per event.
    private static final long MAX_CHARS = 20_000_000;
    private static final int MAX_PERIODS = 20_000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT);
    private static final Pattern BYDAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
    private static final Pattern WEEKS = Pattern.compile("P(\\d+)W");

    /**
     * Read a feed and return the occurrences that overlap [from, to), sorted by start. Times are
     * shown in the zone of from; floating times use the feed's X-WR-TIMEZONE, or that zone.
     */
    static List<Event> parse(BufferedReader in, ZonedDateTime from, ZonedDateTime to, boolean titles) throws IOException {
        Reader r = new Reader(from, to, titles);
        StringBuilder pending = null;
        long chars = 0;
        for (String line; (line = in.readLine()) != null; ) {
            chars += line.length();
            if (chars > MAX_CHARS) throw new IOException("calendar feed too large");
            // A line starting with a space or tab continues the one before it (RFC 5545 folding).
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                if (pending != null) pending.append(line, 1, line.length());
                continue;
            }
            if (pending != null) r.line(pending.toString());
            pending = new StringBuilder(line);
        }
        if (pending != null) r.line(pending.toString());
        if (!r.sawCalendar) throw new NotACalendar();
        return r.finish();
    }

    /** Sort by start, all-day events first among those starting together. */
    static void sortByStart(List<Event> events) {
        Collections.sort(events, (x, y) -> x.start != y.start ? Long.compare(x.start, y.start)
                : Boolean.compare(!x.allDay, !y.allDay));
    }

    /** A DATE (all-day) or a DATE-TIME pinned to a zone: UTC, its TZID, or the calendar's zone if floating. */
    private static final class Time {
        final LocalDate date;
        final ZonedDateTime dateTime;

        Time(LocalDate date, ZonedDateTime dateTime) {
            this.date = date;
            this.dateTime = dateTime;
        }

        boolean allDay() {
            return dateTime == null;
        }

        /** What EXDATE and RECURRENCE-ID are matched on: the date, or the instant whatever its zone. */
        String key() {
            return allDay() ? "d" + date : "t" + dateTime.toInstant().toEpochMilli();
        }
    }

    /** One VEVENT as read, before expansion. */
    private static final class Raw {
        String uid = "", summary = "", status = "", rrule;
        Time start, end, recurrenceId;
        Duration duration;
        final List<Time> exdates = new ArrayList<>();

        boolean cancelled() {
            return status.equals("CANCELLED");
        }
    }

    private static final class Reader {
        final ZonedDateTime from, to;
        final long fromMs, toMs;
        final ZoneId shownZone;
        final boolean titles;
        ZoneId calZone;
        Raw cur;
        boolean sawCalendar;
        int nested; // depth of sub-components (VALARM) inside the current VEVENT
        final List<Raw> masters = new ArrayList<>(), overrides = new ArrayList<>();
        final List<Event> out = new ArrayList<>();

        Reader(ZonedDateTime from, ZonedDateTime to, boolean titles) {
            this.from = from;
            this.to = to;
            fromMs = from.toInstant().toEpochMilli();
            toMs = to.toInstant().toEpochMilli();
            shownZone = from.getZone();
            calZone = shownZone;
            this.titles = titles;
        }

        void line(String l) {
            int colon = valueStart(l);
            if (colon < 0) return;
            String[] head = l.substring(0, colon).split(";");
            String name = head[0].trim().toUpperCase(Locale.ROOT);
            String value = l.substring(colon + 1);
            Map<String, String> params = new HashMap<>();
            for (int i = 1; i < head.length; i++) {
                int eq = head[i].indexOf('=');
                if (eq > 0) params.put(head[i].substring(0, eq).trim().toUpperCase(Locale.ROOT), unquote(head[i].substring(eq + 1)));
            }
            if (name.equals("BEGIN")) {
                if (value.trim().equalsIgnoreCase("VCALENDAR")) sawCalendar = true;
                if (cur != null) nested++;
                else if (value.trim().equalsIgnoreCase("VEVENT")) cur = new Raw();
                return;
            }
            if (name.equals("END")) {
                if (cur == null) return;
                if (nested > 0) nested--;
                else if (value.trim().equalsIgnoreCase("VEVENT")) { endEvent(cur); cur = null; }
                return;
            }
            if (cur == null) {
                if (name.equals("X-WR-TIMEZONE")) calZone = zoneOr(value, calZone);
                return;
            }
            if (nested > 0) return;
            try {
                switch (name) {
                    case "UID": cur.uid = value.trim(); break;
                    case "SUMMARY": cur.summary = unescape(value).trim(); break;
                    case "STATUS": cur.status = value.trim().toUpperCase(Locale.ROOT); break;
                    case "DTSTART": cur.start = time(value, params); break;
                    case "DTEND": cur.end = time(value, params); break;
                    case "DURATION": cur.duration = duration(value.trim()); break;
                    case "RRULE": cur.rrule = value.trim(); break;
                    case "RECURRENCE-ID": cur.recurrenceId = time(value, params); break;
                    case "EXDATE":
                        for (String v : value.split(",")) if (!v.trim().isEmpty()) cur.exdates.add(time(v, params));
                        break;
                    default: break;
                }
            } catch (DateTimeException | IllegalArgumentException e) {
                // A malformed property is skipped; without a DTSTART the event is dropped.
            }
        }

        Time time(String value, Map<String, String> params) {
            String v = value.trim();
            if ("DATE".equalsIgnoreCase(params.get("VALUE")) || v.length() == 8) return new Time(LocalDate.parse(v, DATE), null);
            boolean utc = v.endsWith("Z") || v.endsWith("z");
            LocalDateTime t = LocalDateTime.parse(utc ? v.substring(0, v.length() - 1) : v, DATE_TIME);
            String tzid = params.get("TZID");
            ZoneId zone = utc ? ZoneOffset.UTC : tzid != null ? zoneOr(tzid, calZone) : calZone;
            return new Time(null, ZonedDateTime.of(t, zone));
        }

        void endEvent(Raw r) {
            if (r.start == null) return;
            if (r.end == null || r.end.allDay() != r.start.allDay()) {
                if (r.start.allDay()) {
                    long days = r.duration != null ? Math.max(1, r.duration.toDays()) : 1;
                    r.end = new Time(r.start.date.plusDays(days), null);
                } else {
                    r.end = new Time(null, r.duration != null ? r.start.dateTime.plus(r.duration) : r.start.dateTime);
                }
            }
            if (r.recurrenceId != null) overrides.add(r);
            else if (r.rrule != null) { if (!r.cancelled()) masters.add(r); }
            else if (!r.cancelled()) emit(r.start, r.end, r.summary);
        }

        /** Keep an occurrence if it overlaps the window. */
        void emit(Time s, Time e, String title) {
            long a, b;
            if (s.allDay()) {
                a = s.date.atStartOfDay(shownZone).toInstant().toEpochMilli();
                b = e.date.atStartOfDay(shownZone).toInstant().toEpochMilli();
                if (b <= a) b = s.date.plusDays(1).atStartOfDay(shownZone).toInstant().toEpochMilli();
            } else {
                a = s.dateTime.toInstant().toEpochMilli();
                b = Math.max(a, e.dateTime.toInstant().toEpochMilli());
            }
            boolean overlaps = a < toMs && (b > fromMs || (a == b && a >= fromMs));
            if (overlaps) out.add(new Event(a, b, s.allDay(), titles ? title : ""));
        }

        List<Event> finish() {
            Map<String, Set<String>> replaced = new HashMap<>();
            for (Raw o : overrides) {
                Set<String> keys = replaced.get(o.uid);
                if (keys == null) replaced.put(o.uid, keys = new HashSet<>());
                keys.add(o.recurrenceId.key());
            }
            for (Raw m : masters) expand(m, replaced.get(m.uid));
            // A moved occurrence shows at its new time; a cancelled one just stays removed.
            for (Raw o : overrides) if (!o.cancelled()) emit(o.start, o.end, o.summary);
            sortByStart(out);
            return out;
        }

        /** Walk a recurring event's occurrences up to the end of the window, emitting those inside it. */
        void expand(Raw m, Set<String> replaced) {
            Map<String, String> rule = new HashMap<>();
            for (String part : m.rrule.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) rule.put(part.substring(0, eq).trim().toUpperCase(Locale.ROOT), part.substring(eq + 1).trim().toUpperCase(Locale.ROOT));
            }
            String freq = rule.get("FREQ");
            if (freq == null || !(freq.equals("DAILY") || freq.equals("WEEKLY") || freq.equals("MONTHLY") || freq.equals("YEARLY"))) return;
            int interval = Math.max(1, number(rule.get("INTERVAL"), 1));
            int count = number(rule.get("COUNT"), -1);
            Time until = null;
            try {
                if (rule.containsKey("UNTIL")) until = time(rule.get("UNTIL"), Collections.<String, String>emptyMap());
            } catch (DateTimeException | IllegalArgumentException e) {
                return;
            }
            Set<String> ex = new HashSet<>();
            for (Time t : m.exdates) ex.add(t.key());

            boolean allDay = m.start.allDay();
            // Occurrences keep the start's wall-clock time in its own zone, so DST changes don't move them.
            ZoneId zone = allDay ? shownZone : m.start.dateTime.getZone();
            LocalDate d0 = allDay ? m.start.date : m.start.dateTime.toLocalDate();
            LocalTime t0 = allDay ? null : m.start.dateTime.toLocalTime();
            long spanDays = allDay ? Math.max(1, ChronoUnit.DAYS.between(m.start.date, m.end.date)) : 0;
            Duration span = allDay ? null : Duration.between(m.start.dateTime, m.end.dateTime);
            long padDays = 1 + (allDay ? spanDays : span.toDays());
            LocalDate winFrom = from.withZoneSameInstant(zone).toLocalDate().minusDays(padDays);
            LocalDate winTo = to.withZoneSameInstant(zone).toLocalDate().plusDays(1);
            DayOfWeek wkst = day(rule.get("WKST"), DayOfWeek.MONDAY);

            int n = 0;
            // Without COUNT nothing needs counting from the start, so jump close to the window.
            long period = count < 0 ? firstPeriod(freq, d0, winFrom, interval, wkst) : 0;
            for (int i = 0; i < MAX_PERIODS; i++, period++) {
                long k = period * interval;
                if (periodStart(freq, d0, k, wkst).isAfter(winTo)) return;
                for (LocalDate d : datesIn(freq, d0, k, rule, wkst)) {
                    if (d.isBefore(d0)) continue;
                    if (d.isAfter(winTo)) return;
                    n++;
                    if (count >= 0 && n > count) return;
                    Time occ = allDay ? new Time(d, null) : new Time(null, ZonedDateTime.of(d, t0, zone));
                    if (until != null && after(occ, until)) return;
                    if (d.isBefore(winFrom)) continue;
                    String key = occ.key();
                    if (ex.contains(key) || (replaced != null && replaced.contains(key))) continue;
                    Time end = allDay ? new Time(d.plusDays(spanDays), null) : new Time(null, occ.dateTime.plus(span));
                    emit(occ, end, m.summary);
                }
            }
        }

        boolean after(Time occ, Time until) {
            if (!occ.allDay() && !until.allDay()) return occ.dateTime.toInstant().isAfter(until.dateTime.toInstant());
            LocalDate occDate = occ.allDay() ? occ.date : occ.dateTime.toLocalDate();
            LocalDate untilDate = until.allDay() ? until.date : until.dateTime.withZoneSameInstant(occ.allDay() ? shownZone : occ.dateTime.getZone()).toLocalDate();
            return occDate.isAfter(untilDate);
        }
    }

    // ------------------------------------------------------------ recurrence periods

    private static LocalDate periodStart(String freq, LocalDate d0, long k, DayOfWeek wkst) {
        switch (freq) {
            case "DAILY": return d0.plusDays(k);
            case "WEEKLY": return d0.with(TemporalAdjusters.previousOrSame(wkst)).plusWeeks(k);
            case "MONTHLY": return d0.withDayOfMonth(1).plusMonths(k);
            default: return d0.withDayOfYear(1).plusYears(k);
        }
    }

    /** Period index to start from so the walk begins just before the window. */
    private static long firstPeriod(String freq, LocalDate d0, LocalDate winFrom, int interval, DayOfWeek wkst) {
        if (!winFrom.isAfter(d0)) return 0;
        long units;
        switch (freq) {
            case "DAILY": units = ChronoUnit.DAYS.between(d0, winFrom); break;
            case "WEEKLY": units = ChronoUnit.WEEKS.between(periodStart(freq, d0, 0, wkst), winFrom); break;
            case "MONTHLY": units = ChronoUnit.MONTHS.between(d0.withDayOfMonth(1), winFrom.withDayOfMonth(1)); break;
            default: units = winFrom.getYear() - d0.getYear(); break;
        }
        return Math.max(0, units / interval - 1);
    }

    /** The candidate dates in period k (k already multiplied by INTERVAL), in order. */
    private static List<LocalDate> datesIn(String freq, LocalDate d0, long k, Map<String, String> rule, DayOfWeek wkst) {
        List<LocalDate> dates = new ArrayList<>();
        String byday = rule.get("BYDAY"), bymonthday = rule.get("BYMONTHDAY"), bymonth = rule.get("BYMONTH");
        switch (freq) {
            case "DAILY":
                dates.add(d0.plusDays(k));
                break;
            case "WEEKLY": {
                LocalDate ws = periodStart(freq, d0, k, wkst);
                if (byday == null) dates.add(ws.plusDays(offset(d0.getDayOfWeek(), wkst)));
                else for (Matcher mt = BYDAY.matcher(byday); mt.find(); ) {
                    dates.add(ws.plusDays(offset(day(mt.group(2), DayOfWeek.MONDAY), wkst)));
                }
                break;
            }
            case "MONTHLY": {
                LocalDate first = periodStart(freq, d0, k, wkst);
                inMonth(first, d0, byday, bymonthday, dates);
                break;
            }
            default: {
                LocalDate jan1 = periodStart(freq, d0, k, wkst);
                if (bymonth == null) inMonth(jan1.withMonth(d0.getMonthValue()), d0, byday, bymonthday, dates);
                else for (String mo : bymonth.split(",")) {
                    int month = number(mo, 0);
                    if (month >= 1 && month <= 12) inMonth(jan1.withMonth(month), d0, byday, bymonthday, dates);
                }
                break;
            }
        }
        Collections.sort(dates);
        return dates;
    }

    /** Dates in the month starting at first: by BYDAY (with ordinals), by BYMONTHDAY, or on DTSTART's day. */
    private static void inMonth(LocalDate first, LocalDate d0, String byday, String bymonthday, List<LocalDate> dates) {
        int len = first.lengthOfMonth();
        if (byday != null) {
            for (Matcher mt = BYDAY.matcher(byday); mt.find(); ) {
                DayOfWeek dow = day(mt.group(2), DayOfWeek.MONDAY);
                int nth = mt.group(1) == null ? 0 : number(mt.group(1).replace("+", ""), 0);
                if (nth == 0) {
                    for (LocalDate d = first.with(TemporalAdjusters.nextOrSame(dow)); d.getMonth() == first.getMonth(); d = d.plusWeeks(1)) dates.add(d);
                } else {
                    LocalDate d = nth > 0
                            ? first.with(TemporalAdjusters.nextOrSame(dow)).plusWeeks(nth - 1)
                            : first.withDayOfMonth(len).with(TemporalAdjusters.previousOrSame(dow)).minusWeeks(-nth - 1);
                    if (d.getMonth() == first.getMonth()) dates.add(d);
                }
            }
        } else if (bymonthday != null) {
            for (String s : bymonthday.split(",")) {
                int day = number(s, 0);
                if (day < 0) day = len + 1 + day;
                if (day >= 1 && day <= len) dates.add(first.withDayOfMonth(day));
            }
        } else if (d0.getDayOfMonth() <= len) {
            dates.add(first.withDayOfMonth(d0.getDayOfMonth())); // e.g. the 31st is skipped in shorter months
        }
    }

    // ------------------------------------------------------------ small helpers

    private static long offset(DayOfWeek dow, DayOfWeek wkst) {
        return (dow.getValue() - wkst.getValue() + 7) % 7;
    }

    private static DayOfWeek day(String code, DayOfWeek fallback) {
        if (code == null) return fallback;
        switch (code) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return fallback;
        }
    }

    private static int number(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** DURATION values such as PT1H30M, P1D, P1DT2H or P2W. */
    private static Duration duration(String v) {
        Matcher w = WEEKS.matcher(v.startsWith("+") ? v.substring(1) : v);
        if (w.matches()) return Duration.ofDays(7L * Integer.parseInt(w.group(1)));
        return Duration.parse(v.startsWith("+") ? v.substring(1) : v);
    }

    /** Resolve a TZID, also when it carries a path prefix such as /mozilla.org/.../Australia/Melbourne. */
    static ZoneId zoneOr(String id, ZoneId fallback) {
        String s = id.trim();
        while (!s.isEmpty()) {
            try {
                return ZoneId.of(s);
            } catch (DateTimeException e) {
                int slash = s.indexOf('/');
                if (slash < 0) break;
                s = s.substring(slash + 1);
            }
        }
        return fallback;
    }

    /** Index of the colon that ends the property name and parameters (colons inside quotes don't count). */
    private static int valueStart(String l) {
        boolean quoted = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** TEXT escapes: \\, \;, \, and \n (shown as a space on a one-line title). */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' || n == 'N' ? ' ' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
