package com.mihir.tvhome;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Desktop check of IcsParser and Agenda against test/fixture.ics (made-up events). No Android or
 * test library needed. From launcher/:
 *   javac -d build/test src/com/mihir/tvhome/IcsParser.java src/com/mihir/tvhome/Agenda.java test/com/mihir/tvhome/IcsParserTest.java
 *   java -cp build/test com.mihir.tvhome.IcsParserTest test/fixture.ics
 */
public class IcsParserTest {

    static final ZoneId MEL = ZoneId.of("Australia/Melbourne");
    static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static int failures;

    public static void main(String[] args) throws Exception {
        String fixture = args.length > 0 ? args[0] : "test/fixture.ics";

        // Today and tomorrow (plus the day after, to see COUNT stop).
        check("Oct 22-24", parse(fixture, day(2026, 10, 22), day(2026, 10, 25), true), Arrays.asList(
                "all-day 2026-10-19..2026-10-24 School holidays",  // multi-day all-day, end exclusive
                "all-day 2026-10-22..2026-10-23 Sam's birthday",    // yearly all-day
                "2026-10-22 06:30-07:30 Gym",
                "2026-10-22 08:00-08:15 Medication",                // DURATION instead of DTEND
                "2026-10-22 12:00-13:00 Course",                    // COUNT=3, 2nd
                "2026-10-22 13:00-13:30 Haircut",                   // TZID with a path prefix
                "2026-10-22 15:30-16:00 Pick up kids, then groceries", // folded line, escaped comma, VALARM skipped
                "2026-10-22 17:30-18:30 Piano lesson (moved)",      // RECURRENCE-ID override; 16:00 gone
                "2026-10-22 19:00-21:00 Book club",                 // MONTHLY BYDAY=4TH
                "2026-10-22 19:30-20:30 Call with London",          // UTC time, shown in Melbourne (AEDT)
                "2026-10-23 08:00-08:15 Medication",                // Gym 06:30 cancelled by override
                "2026-10-23 09:00-09:15 Rent",                      // MONTHLY on the 23rd
                "2026-10-23 12:00-13:00 Course",                    // COUNT=3, last
                "2026-10-23 15:00-16:00 Team review",               // WEEKLY INTERVAL=2
                "2026-10-24 06:30-07:30 Gym",
                "2026-10-24 08:00-08:15 Medication"));              // no Course: COUNT reached
        // Standup on Thu 22 is EXDATEd, Dentist is cancelled, Old class ended by UNTIL, Pay day skips 31-less months.

        // Melbourne DST starts Sun 4 Oct 2026 at 2:00. Local-time series keep their wall-clock time;
        // the UTC-anchored series moves from 9:00 to 10:00 local.
        check("DST start", parse(fixture, day(2026, 10, 3), day(2026, 10, 6), true), Arrays.asList(
                "2026-10-03 06:30-07:30 Gym",
                "2026-10-03 08:00-08:15 Medication",
                "2026-10-04 06:30-07:30 Gym",
                "2026-10-04 08:00-08:15 Medication",
                "2026-10-04 09:30-11:00 Sunday service",
                "2026-10-04 10:00-11:00 Online class",
                "2026-10-05 06:30-07:30 Gym",
                "2026-10-05 08:00-08:15 Medication",
                "2026-10-05 09:30-10:00 Standup"));
        check("before DST", parse(fixture, day(2026, 9, 27), day(2026, 9, 28), true), Arrays.asList(
                "2026-09-27 08:00-08:15 Medication",
                "2026-09-27 09:00-10:00 Online class",
                "2026-09-27 09:30-11:00 Sunday service"));
        // Melbourne DST ends Sun 5 Apr 2026 at 3:00; the daily 8:00 stays at 8:00.
        check("DST end", parse(fixture, day(2026, 4, 5), day(2026, 4, 6), true), Arrays.asList(
                "2026-04-05 08:00-08:15 Medication"));
        check("titles off", parse(fixture, day(2026, 10, 24), day(2026, 10, 25), false), Arrays.asList(
                "2026-10-24 06:30-07:30 ",
                "2026-10-24 08:00-08:15 "));

        // What the card shows at different times on Thu 22 Oct.
        List<IcsParser.Event> twoDays = parse(fixture, day(2026, 10, 22), day(2026, 10, 24), true);
        checkAgenda("05:00", twoDays, at(2026, 10, 22, 5, 0), false,
                Arrays.asList("School holidays", "Sam's birthday"),
                Arrays.asList("06:30 Gym", "08:00 Medication", "12:00 Course", "13:00 Haircut"));
        checkAgenda("18:00", twoDays, at(2026, 10, 22, 18, 0), false,
                Arrays.asList("School holidays", "Sam's birthday"),
                Arrays.asList("17:30 Piano lesson (moved)", "19:00 Book club", "19:30 Call with London"));
        checkAgenda("22:00", twoDays, at(2026, 10, 22, 22, 0), true,
                Arrays.asList("School holidays", "Sam's birthday"),
                Arrays.asList("all-day School holidays", "08:00 Medication", "09:00 Rent"));
        checkAgenda("empty", new ArrayList<>(), at(2026, 10, 22, 9, 0), true,
                new ArrayList<>(), new ArrayList<>());

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static ZonedDateTime day(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(MEL);
    }

    static ZonedDateTime at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, MEL);
    }

    static List<IcsParser.Event> parse(String file, ZonedDateTime from, ZonedDateTime to, boolean titles) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return IcsParser.parse(r, from, to, titles);
        }
    }

    static String show(IcsParser.Event e) {
        ZonedDateTime s = Instant.ofEpochMilli(e.start).atZone(MEL), en = Instant.ofEpochMilli(e.end).atZone(MEL);
        if (e.allDay) return "all-day " + s.toLocalDate() + ".." + en.toLocalDate() + " " + e.title;
        return F.format(s) + "-" + en.toLocalTime() + " " + e.title;
    }

    static String brief(IcsParser.Event e) {
        return e.allDay ? "all-day " + e.title : Instant.ofEpochMilli(e.start).atZone(MEL).toLocalTime() + " " + e.title;
    }

    static void check(String name, List<IcsParser.Event> got, List<String> want) {
        List<String> g = new ArrayList<>();
        for (IcsParser.Event e : got) g.add(show(e));
        report(name, g, want);
    }

    static void checkAgenda(String name, List<IcsParser.Event> events, ZonedDateTime now, boolean tomorrow,
                            List<String> allDay, List<String> rows) {
        Agenda a = Agenda.pick(events, now);
        List<String> gAll = new ArrayList<>(), gRows = new ArrayList<>();
        for (IcsParser.Event e : a.allDayToday) gAll.add(e.title);
        for (IcsParser.Event e : a.events) gRows.add(brief(e));
        report("agenda " + name + " tomorrow", Arrays.asList(String.valueOf(a.tomorrow)), Arrays.asList(String.valueOf(tomorrow)));
        report("agenda " + name + " all-day", gAll, allDay);
        report("agenda " + name + " rows", gRows, rows);
    }

    static void report(String name, List<String> got, List<String> want) {
        if (got.equals(want)) {
            System.out.println("ok   " + name);
            return;
        }
        failures++;
        System.out.println("FAIL " + name);
        System.out.println("  want: " + want);
        System.out.println("  got:  " + got);
    }
}
