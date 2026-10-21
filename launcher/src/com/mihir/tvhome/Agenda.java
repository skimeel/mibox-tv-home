package com.mihir.tvhome;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the schedule card shows at a given moment: today's all-day events as one line at the top,
 * then up to four events still to come today, or, if none are left, tomorrow's first three.
 * Plain Java, so it is tested on a desktop JVM with the parser.
 */
final class Agenda {

    static final int TODAY_MAX = 4, TOMORROW_MAX = 3;

    final List<IcsParser.Event> allDayToday = new ArrayList<>();
    /** Today's events that haven't ended yet, or tomorrow's first ones when none are left today. */
    final List<IcsParser.Event> events = new ArrayList<>();
    final boolean tomorrow;

    private Agenda(boolean tomorrow) {
        this.tomorrow = tomorrow;
    }

    boolean isEmpty() {
        return allDayToday.isEmpty() && events.isEmpty();
    }

    /** events must be sorted by start (as IcsParser returns them). */
    static Agenda pick(List<IcsParser.Event> all, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        long nowMs = now.toInstant().toEpochMilli();
        long today = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        long tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long dayAfter = now.toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli();

        List<IcsParser.Event> allDay = new ArrayList<>(), left = new ArrayList<>();
        for (IcsParser.Event e : all) {
            if (e.allDay) {
                if (e.start < tomorrow && e.end > today) allDay.add(e);
            } else if (e.start < tomorrow && (e.end > nowMs || e.start >= nowMs) && left.size() < TODAY_MAX) {
                left.add(e); // not ended yet (a zero-length event counts until it starts)
            }
        }
        Agenda a = new Agenda(left.isEmpty());
        a.allDayToday.addAll(allDay);
        if (!left.isEmpty()) {
            a.events.addAll(left);
            return a;
        }
        // Nothing left today: tomorrow's first three, all-day ones first.
        for (IcsParser.Event e : all) {
            if (a.events.size() < TOMORROW_MAX && e.allDay && e.start < dayAfter && e.end > tomorrow) a.events.add(e);
        }
        for (IcsParser.Event e : all) {
            if (a.events.size() < TOMORROW_MAX && !e.allDay && e.start >= tomorrow && e.start < dayAfter) a.events.add(e);
        }
        return a;
    }
}
