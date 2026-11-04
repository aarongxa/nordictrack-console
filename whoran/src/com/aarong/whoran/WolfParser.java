package com.aarong.whoran;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of parse_wolf.sh: turns one iFit wolf log (/sdcard/.wolflogs/YYYY-MM-DD_logs.txt)
 * into one Workout per completed workout (one that reached WorkoutResults), and also reports the
 * last treadmill activity (state change or speed change). Plain Java, no Android APIs, so it can
 * be tested on a desktop JVM against parse_wolf.sh.
 */
final class WolfParser {
    static final class Workout {
        String id, date, startTime, endTime, durationSource, distanceSource;
        long durationSeconds, wallSeconds, pausedSeconds;
        int pauses;
        double startSec, endSec;              // seconds after midnight of date (may exceed 86400)
        Double distanceMeters, distanceMiles, avgMph, avgMphKph, maxMph, calories;
        final Map<String, Double[]> stats = new LinkedHashMap<>();   // duration_ms, last_stat_time, last_stat_value

        /** JSON in the same shape parse_wolf.sh prints, plus any extra string fields. */
        String toJson(String... extra) {
            StringBuilder b = new StringBuilder("{");
            str(b, "workout_id", id).append(','); str(b, "date", date).append(',');
            str(b, "start_time", startTime).append(','); str(b, "end_time", endTime).append(',');
            b.append("\"duration_seconds\":").append(durationSeconds).append(',');
            str(b, "duration_source", durationSource).append(',');
            b.append("\"wall_seconds\":").append(wallSeconds).append(',');
            b.append("\"paused_seconds\":").append(pausedSeconds).append(',');
            b.append("\"pauses\":").append(pauses).append(',');
            b.append("\"distance_meters\":").append(num(distanceMeters)).append(',');
            str(b, "distance_source", distanceSource).append(',');
            b.append("\"distance_miles\":").append(fix(distanceMiles, 3)).append(',');
            b.append("\"avg_mph\":").append(fix(avgMph, 2)).append(',');
            b.append("\"avg_mph_kph_events\":").append(fix(avgMphKph, 2)).append(',');
            b.append("\"max_mph\":").append(fix(maxMph, 2)).append(',');
            b.append("\"calories\":").append(fix(calories, 1)).append(',');
            b.append("\"stats\":{");
            boolean first = true;
            for (Map.Entry<String, Double[]> e : stats.entrySet()) {
                if (!first) b.append(',');
                first = false;
                Double[] v = e.getValue();
                b.append('"').append(esc(e.getKey())).append("\":{\"duration_ms\":").append(num(v[0]))
                        .append(",\"last_stat_time\":").append(num(v[1]))
                        .append(",\"last_stat_value\":").append(num(v[2])).append('}');
            }
            b.append('}');
            for (int i = 0; i + 1 < extra.length; i += 2) { b.append(','); str(b, extra[i], extra[i + 1]); }
            return b.append('}').toString();
        }
    }

    static final class Result {
        final List<Workout> workouts = new ArrayList<>();
        double lastActivitySec = -1;          // seconds after midnight of the log date, or -1
        String lastState;                     // last FitPro state seen, or null
        String unfinished;                    // start time of a workout with no results yet
    }

    private static final Pattern LINE = Pattern.compile("^\\[[0-9]+\\] [0-9]+ ([0-9]{2}):([0-9]{2}):([0-9]{2}(?:\\.[0-9]+)?)");
    private static final Pattern RUNNING = Pattern.compile("\\[Trace:Workout\\] Set current time \\(r\\) to [0-9]+ from RunningTime ([0-9.]+)");
    private static final Pattern METERS = Pattern.compile("\\[Trace:Workout\\] SetMeters ([0-9.]+)");
    private static final Pattern KPH = Pattern.compile("\\[Trace:FitnessConsole\\] (Kph changed from|Clamped Kph from) ");

    private final String date;
    private final Result res = new Result();
    // parser state (names follow parse_wolf.sh)
    private String state = "Unknown";
    private double curKph = 0, dayoff = 0;
    private Double lastt;
    private boolean inWo, haveResults, statsOpen;
    private Double startT, endT, pauseT, runSecs, meters, segT;
    private double pauseTotal, kphArea, kphTime, maxKph;
    private int npauses;
    private final List<String> order = new ArrayList<>();
    private final Map<String, String[]> st = new LinkedHashMap<>();   // dur, time, val (raw strings, "" = none)
    private String curk = "";

    private WolfParser(String date) { this.date = date; reset(); }

    static Result parse(Reader in, String date) throws IOException {
        WolfParser p = new WolfParser(date);
        BufferedReader r = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in, 65536);
        String line;
        while ((line = r.readLine()) != null) p.line(line);
        p.end();
        return p.res;
    }

    private void reset() {
        inWo = false; haveResults = false; statsOpen = false;
        startT = null; endT = null; pauseTotal = 0; pauseT = null; npauses = 0;
        runSecs = null; meters = null; kphArea = 0; kphTime = 0; segT = null; maxKph = 0;
        order.clear(); st.clear();
    }

    private void speedTo(double k, double t) {
        if ("Workout".equals(state) && segT != null) { kphArea += curKph * (t - segT); kphTime += t - segT; }
        segT = t;
        curKph = k;
        if (("Workout".equals(state) || "WarmUp".equals(state)) && curKph > maxKph) maxKph = curKph;
    }

    private static String after(String s, String marker, boolean last) {
        int i = last ? s.lastIndexOf(marker) : s.indexOf(marker);
        return i < 0 ? null : s.substring(i + marker.length());
    }

    private static String firstToken(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t' && s.charAt(i) != '\r') i++;
        return s.substring(0, i);
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t' || s.charAt(e - 1) == '\r')) e--;
        return s.substring(0, e);
    }

    private static double toNum(String s) {      // awk style: leading number, else 0
        Matcher m = Pattern.compile("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?").matcher(s.trim());
        return m.find() ? Double.parseDouble(m.group()) : 0;
    }

    private void line(String s) {
        Matcher lm = LINE.matcher(s);
        if (!lm.find()) return;
        double t = Integer.parseInt(lm.group(1)) * 3600 + Integer.parseInt(lm.group(2)) * 60 + Double.parseDouble(lm.group(3)) + dayoff;
        if (lastt != null && t < lastt - 43200) { dayoff += 86400; t += 86400; }
        lastt = t;

        boolean isStats = s.contains("[Trace:STATS]");
        if (statsOpen && order.size() >= 1 && "xscore".equals(order.get(order.size() - 1)) && !isStats) {
            statsOpen = false; emit(); reset();
        }
        if (s.contains("[Trace:FitPro] Changed state from: ")) {
            String from = after(s, "Changed state from: ", false);
            int k = from.indexOf(" to: ");
            if (k >= 0) from = from.substring(0, k);
            String to = rtrim(after(s, " to: ", true));
            speedTo(curKph, t);
            if ("Workout".equals(to) && !"Paused".equals(from)) { reset(); inWo = true; startT = t; segT = t; }
            if (inWo && "Paused".equals(to)) { pauseT = t; npauses++; }
            if (inWo && "Paused".equals(from) && pauseT != null) { pauseTotal += t - pauseT; pauseT = null; }
            if (inWo && "WorkoutResults".equals(to)) { endT = t; haveResults = true; statsOpen = true; }
            if (inWo && ("Idle".equals(to) || "WarmUp".equals(to)) && !haveResults) reset();
            state = to;
            res.lastActivitySec = t; res.lastState = to;
            return;
        }
        if (KPH.matcher(s).find()) {
            String v = after(s, " to ", true);
            v = rtrim(v);
            if (v.endsWith(".")) v = v.substring(0, v.length() - 1);
            speedTo(toNum(v), t);
            res.lastActivitySec = t;
            return;
        }
        if (inWo && !haveResults) {
            Matcher m = RUNNING.matcher(s);
            if (m.find()) { runSecs = toNum(m.group(1)); return; }
            m = METERS.matcher(s);
            if (m.find()) { meters = toNum(firstToken(after(s, "SetMeters ", false))); return; }
        }
        if (statsOpen && s.contains("[Trace:STATS] TYPE - ")) {
            String k = firstToken(after(s, "TYPE - ", false));
            String d = s.contains("DURATION - ") ? firstToken(after(s, "DURATION - ", false)) : "";
            if ("pulse".equals(k) && order.size() > 0) { statsOpen = false; emit(); reset(); return; }
            if (!st.containsKey(k)) { order.add(k); st.put(k, new String[] { d, "", "" }); }
            curk = k;
            return;
        }
        if (statsOpen && s.contains("[Trace:STATS] Last Stat Time - ")) {
            String a = firstToken(after(s, "Last Stat Time - ", false));
            String b = s.contains("LastStat Value - ") ? rtrim(after(s, "LastStat Value - ", false)) : "";
            String[] v = st.get(curk);
            if (!"".equals(curk) && v != null && "".equals(v[1])) { v[1] = a; v[2] = b; }
        }
    }

    private void end() {
        if (statsOpen) emit();
        else if (inWo && !haveResults && startT != null) res.unfinished = hms(startT);
    }

    private static String hms(double s) {
        long x = (long) (s + 0.5) % 86400;
        return String.format(Locale.US, "%02d:%02d:%02d", x / 3600, (x % 3600) / 60, x % 60);
    }

    private static Double val(String s) { return s == null || s.isEmpty() ? null : toNum(s); }

    private void emit() {
        Workout w = new Workout();
        w.date = date;
        w.id = (date + "T" + hms(startT)).replace(':', '-');
        w.startTime = hms(startT);
        w.endTime = hms(endT);
        w.startSec = startT; w.endSec = endT;
        double wall = endT - startT, active;
        String[] dist = st.get("distance");
        if (runSecs != null) { active = runSecs; w.durationSource = "running_time"; }
        else if (dist != null && !dist[0].isEmpty()) { active = toNum(dist[0]) / 1000; w.durationSource = "stats_duration"; }
        else { active = wall - pauseTotal; w.durationSource = "wall_minus_pauses"; }
        Double dm;
        if (dist != null && !dist[2].isEmpty()) { dm = toNum(dist[2]); w.distanceSource = "stats"; }
        else if (meters != null) { dm = meters; w.distanceSource = "setmeters"; }
        else { dm = null; w.distanceSource = "none"; }
        w.distanceMeters = dm;
        w.distanceMiles = dm == null ? null : dm / 1609.344;
        w.avgMph = (w.distanceMiles != null && active > 0) ? w.distanceMiles / (active / 3600) : null;
        w.avgMphKph = kphTime > 0 ? (kphArea / kphTime) / 1.609344 : null;
        w.maxMph = maxKph > 0 ? maxKph / 1.609344 : null;
        String[] cal = st.get("calories");
        w.calories = cal != null && !cal[2].isEmpty() ? toNum(cal[2]) : null;
        w.durationSeconds = (long) (active + 0.5);
        w.wallSeconds = (long) (wall + 0.5);
        w.pausedSeconds = (long) (pauseTotal + 0.5);
        w.pauses = npauses;
        for (String k : order) { String[] v = st.get(k); w.stats.put(k, new Double[] { val(v[0]), val(v[1]), val(v[2]) }); }
        res.workouts.add(w);
    }

    // ---- tiny JSON helpers ----
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.toString();
    }
    private static StringBuilder str(StringBuilder b, String k, String v) {
        b.append('"').append(k).append("\":");
        return v == null ? b.append("null") : b.append('"').append(esc(v)).append('"');
    }
    private static String fix(Double v, int places) {
        return v == null ? "null" : String.format(Locale.US, "%." + places + "f", v);
    }
    private static String num(Double v) {     // like awk's default number output (%.6g, integers plain)
        if (v == null) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) (double) v);
        String s = String.format(Locale.US, "%.6g", v);
        if (s.contains("e")) return s;
        if (s.contains(".")) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s = s.substring(0, s.length() - 1); }
        return s;
    }
}
