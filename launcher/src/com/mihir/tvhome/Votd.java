package com.mihir.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouVersion Platform verse of the day: the year list and its USFM passage ids turned into the
 * references the ESV API and the verse card use, e.g. "ROM.8.38-39" to "Romans 8:38–39".
 */
final class Votd {
    private Votd() { }

    /** The 66 USFM book codes and the book names the ESV uses. */
    private static final Map<String, String> BOOKS = new HashMap<>();
    static {
        String[] b = {
            "GEN", "Genesis", "EXO", "Exodus", "LEV", "Leviticus", "NUM", "Numbers", "DEU", "Deuteronomy",
            "JOS", "Joshua", "JDG", "Judges", "RUT", "Ruth", "1SA", "1 Samuel", "2SA", "2 Samuel",
            "1KI", "1 Kings", "2KI", "2 Kings", "1CH", "1 Chronicles", "2CH", "2 Chronicles", "EZR", "Ezra",
            "NEH", "Nehemiah", "EST", "Esther", "JOB", "Job", "PSA", "Psalm", "PRO", "Proverbs",
            "ECC", "Ecclesiastes", "SNG", "Song of Solomon", "ISA", "Isaiah", "JER", "Jeremiah",
            "LAM", "Lamentations", "EZK", "Ezekiel", "DAN", "Daniel", "HOS", "Hosea", "JOL", "Joel",
            "AMO", "Amos", "OBA", "Obadiah", "JON", "Jonah", "MIC", "Micah", "NAM", "Nahum",
            "HAB", "Habakkuk", "ZEP", "Zephaniah", "HAG", "Haggai", "ZEC", "Zechariah", "MAL", "Malachi",
            "MAT", "Matthew", "MRK", "Mark", "LUK", "Luke", "JHN", "John", "ACT", "Acts", "ROM", "Romans",
            "1CO", "1 Corinthians", "2CO", "2 Corinthians", "GAL", "Galatians", "EPH", "Ephesians",
            "PHP", "Philippians", "COL", "Colossians", "1TH", "1 Thessalonians", "2TH", "2 Thessalonians",
            "1TI", "1 Timothy", "2TI", "2 Timothy", "TIT", "Titus", "PHM", "Philemon", "HEB", "Hebrews",
            "JAS", "James", "1PE", "1 Peter", "2PE", "2 Peter", "1JN", "1 John", "2JN", "2 John",
            "3JN", "3 John", "JUD", "Jude", "REV", "Revelation",
        };
        for (int i = 0; i < b.length; i += 2) BOOKS.put(b[i], b[i + 1]);
    }

    static int bookCount() { return BOOKS.size(); }

    // BOOK.C.V, optionally followed by -V2, -C2.V2 or -BOOK.C2.V2 within the same book.
    private static final Pattern ID = Pattern.compile(
            "([1-3]?[A-Z]{2,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:-(?:(?:([1-3]?[A-Z]{2,3})\\.)?(\\d{1,3})\\.)?(\\d{1,3}))?");

    /**
     * {reference, first verse's reference or null for a single verse}, with an en dash in ranges
     * like the bundled list; null if the id isn't one verse or one unbroken range in a known book.
     */
    static String[] reference(String passageId) {
        if (passageId == null) return null;
        Matcher m = ID.matcher(passageId.trim());
        if (!m.matches()) return null;
        String book = BOOKS.get(m.group(1));
        if (book == null || (m.group(4) != null && !m.group(4).equals(m.group(1)))) return null;
        int c = Integer.parseInt(m.group(2)), v = Integer.parseInt(m.group(3));
        if (c == 0 || v == 0) return null;
        String first = book + " " + c + ":" + v;
        if (m.group(6) == null) return new String[]{first, null};
        int c2 = m.group(5) != null ? Integer.parseInt(m.group(5)) : c, v2 = Integer.parseInt(m.group(6));
        if (c2 < c || v2 == 0 || (c2 == c && v2 <= v)) return null;
        String ref = c2 == c ? first + "–" + v2 : first + "–" + c2 + ":" + v2;
        return new String[]{ref, first};
    }

    /**
     * The year list {"data":[{"day":N,"passage_id":"…"},…]} as passage ids by day (index 1 to
     * 366); null if the body isn't that shape or has no usable entry.
     */
    static String[] parseYear(String body) {
        try {
            JSONArray data = new JSONObject(body).getJSONArray("data");
            String[] byDay = new String[367];
            int n = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject e = data.optJSONObject(i);
                if (e == null) continue;
                int day = e.optInt("day", 0);
                String id = e.optString("passage_id", "");
                if (day < 1 || day > 366 || id.isEmpty()) continue;
                byDay[day] = id;
                n++;
            }
            return n == 0 ? null : byDay;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }
}
