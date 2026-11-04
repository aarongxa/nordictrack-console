package com.aarong.whoran;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Treadmill stats, all on the console. Every 15 minutes, on its own thread, unless the treadmill is in use
 * (iFit's in workout screen in front, or the log shows a workout in progress), in which case the check is
 * deferred to the next slot. When the in workout screen closes, one check runs a minute later so the
 * picker shows up right after the workout. Each check:
 *  1. Parses today's and yesterday's iFit wolf logs (WolfParser, same rules as parse_wolf.sh).
 *  2. Opens the "Who ran?" picker once for each new finished workout.
 *  3. After IDLE_MIN minutes without belt activity, POSTs each new workout plus the picked runner to
 *     the treadmill-stats Worker, which stores it in R2 and emails the runner.
 * Sent ids live in files/stats_state.json, so each workout posts once; failures retry at the next check.
 * The first run only records the workouts already in the logs.
 * Upload token: files/upload_token (private). Provisioned by placing it at
 * /sdcard/Download/whoran/.upload_token; it is moved into private storage and the copy deleted.
 * Whitespace, quotes and a BOM are ignored; a file that is empty or malformed is left alone for 2 minutes
 * (it may still be being written), then deleted. The token is never logged. After a token is available,
 * one authenticated GET /stats/aaron logs only its HTTP status, to prove the app reaches the Worker.
 */
final class StatsUploader {
    private static final String TAG = "whoran.stats";
    static final String URL_WORKOUT = "https://treadmill-stats.aarongriffith06.workers.dev/workout";
    private static final File WOLF_DIR = new File(Environment.getExternalStorageDirectory(), ".wolflogs");
    private static final File PICK_DIR = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "whoran");
    private static final long TICK_MS = 15 * 60_000L;
    private static final long FIRST_MS = 20_000L, AFTER_WORKOUT_MS = 60_000L, MIN_GAP_MS = 30_000L;
    private static final long IDLE_MIN = 30, STALE_MIN = 180, PICK_WAIT_MIN = 11, PICK_MAX_AGE_MIN = 120;
    private static final long MIN_SECONDS = 120;          // shorter workouts (belt tests) are ignored

    private final Context ctx;
    private HandlerThread thread;
    private Handler handler;
    private final Map<String, Object[]> cache = new HashMap<>();   // path -> {length, mtime, Result}
    private volatile boolean inWorkout;
    private boolean probed;
    private long lastCycleMs;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (handler != null) { handler.removeCallbacks(this); handler.postDelayed(this, TICK_MS); }
            long now = System.currentTimeMillis();
            if (now - lastCycleMs < MIN_GAP_MS) return;
            lastCycleMs = now;
            try { cycle(); } catch (Throwable t) { Log.w(TAG, "check failed: " + t); }
        }
    };

    StatsUploader(Context c) { ctx = c.getApplicationContext(); }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("treadmill-stats");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(tick, FIRST_MS);
    }

    /** Run a check soon (adb: am start -n com.aarong.whoran/.StartButtonActivity --ez check true). */
    void checkNow() {
        Handler h = handler;
        if (h != null) { h.removeCallbacks(tick); h.postDelayed(tick, 1_000L); }
    }

    /** Called from the overlay service's 5 second foreground poll. */
    void setInWorkout(boolean now) {
        boolean was = inWorkout;
        inWorkout = now;
        Handler h = handler;
        if (was && !now && h != null) { h.removeCallbacks(tick); h.postDelayed(tick, AFTER_WORKOUT_MS); }
    }

    void stop() {
        if (thread == null) return;
        handler.removeCallbacks(tick);
        handler = null;
        thread.quitSafely();
        thread = null;
    }

    // ---------------------------------------------------------------- one cycle
    private void cycle() throws Exception {
        importToken();
        if (!probed) probe();
        if (inWorkout) { Log.i(TAG, "treadmill in use; check deferred"); return; }
        if (WOLF_DIR.list() == null) { Log.w(TAG, "cannot read " + WOLF_DIR); return; }
        long now = System.currentTimeMillis();

        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String[] days = { day.format(new Date(now - 86_400_000L)), day.format(new Date(now)) };
        java.util.List<WolfParser.Workout> all = new java.util.ArrayList<>();
        Map<String, String> dateOf = new HashMap<>();
        long lastActMs = -1; String lastState = null;
        for (String d : days) {
            WolfParser.Result r = parsed(new File(WOLF_DIR, d + "_logs.txt"), d);
            if (r == null) continue;
            for (WolfParser.Workout w : r.workouts) if (!dateOf.containsKey(w.id)) { all.add(w); dateOf.put(w.id, d); }
            if (r.lastActivitySec >= 0) { lastActMs = epochMs(d, r.lastActivitySec); if (r.lastState != null) lastState = r.lastState; }
        }

        JSONObject state = loadState();
        Set<String> sent = toSet(state.optJSONArray("sent"));
        JSONObject opened = state.optJSONObject("picker_opened");
        if (opened == null) opened = new JSONObject();

        if (!state.optBoolean("initialized")) {
            for (WolfParser.Workout w : all) sent.add(w.id);
            state.put("initialized", true);
            saveState(state, sent, opened);
            Log.i(TAG, "first run: recorded " + sent.size() + " existing workouts, none uploaded");
            return;
        }

        boolean idle;
        if (lastActMs < 0) idle = true;
        else {
            long quiet = now - lastActMs;
            boolean active = "Workout".equals(lastState) || "Paused".equals(lastState) || "WarmUp".equals(lastState);
            if (active && quiet < STALE_MIN * 60_000L) { Log.i(TAG, "workout in progress; check deferred"); return; }
            idle = quiet >= IDLE_MIN * 60_000L;
        }

        boolean dirty = false;
        String token = null;
        for (WolfParser.Workout w : all) {
            if (sent.contains(w.id)) continue;
            if (w.durationSeconds < MIN_SECONDS) {
                sent.add(w.id); dirty = true;
                Log.i(TAG, w.id + " skipped, only " + w.durationSeconds + "s");
                continue;
            }
            long endMs = epochMs(w.date, w.endSec);
            String runner = readPick(w.id);
            long openedAt = opened.optLong(w.id, 0);
            if (runner == null && openedAt == 0 && now - endMs < PICK_MAX_AGE_MIN * 60_000L) {
                openPicker(w);
                opened.put(w.id, now); openedAt = now; dirty = true;
            }
            if (!idle) continue;
            if (runner == null) {
                if (openedAt > 0 && now - openedAt < PICK_WAIT_MIN * 60_000L) continue;
                runner = "Unknown";
            }
            if (token == null) token = readToken();
            if (token == null) { Log.w(TAG, "no upload token; " + w.id + " waits"); break; }
            int code = post(w.toJson("runner", runner, "source", "console"), token);
            if (code == 200) {
                sent.add(w.id); dirty = true;
                Log.i(TAG, w.id + " posted for " + runner);
            } else if (code >= 400 && code < 500 && code != 401 && code != 408 && code != 429) {
                sent.add(w.id); dirty = true;
                Log.w(TAG, w.id + " rejected by the Worker, HTTP " + code + "; not retried");
            } else {
                Log.w(TAG, w.id + " post failed, HTTP " + code + "; retry at the next check");
                break;
            }
        }
        // forget picker records older than 3 days
        for (Iterator<String> it = opened.keys(); it.hasNext(); ) {
            String k = it.next();
            if (now - opened.optLong(k, 0) > 3 * 86_400_000L) { it.remove(); dirty = true; }
        }
        if (dirty) saveState(state, sent, opened);
    }

    private WolfParser.Result parsed(File f, String date) {
        if (!f.canRead()) return null;
        Object[] c = cache.get(f.getPath());
        long len = f.length(), mod = f.lastModified();
        if (c != null && (Long) c[0] == len && (Long) c[1] == mod) return (WolfParser.Result) c[2];
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(f), "UTF-8")) {
            WolfParser.Result r = WolfParser.parse(in, date);
            cache.put(f.getPath(), new Object[] { len, mod, r });
            return r;
        } catch (Exception e) {
            Log.w(TAG, "parse " + f + ": " + e);
            return null;
        }
    }

    /** Midnight (console local time, America/Toronto) of date plus seconds. */
    static long epochMs(String date, double sec) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                Integer.parseInt(date.substring(8, 10)), 0, 0, 0);
        return c.getTimeInMillis() + (long) (sec * 1000);
    }

    private void openPicker(WolfParser.Workout w) {
        String summary = String.format(Locale.US, "%.2f mi, %d min",
                w.distanceMiles == null ? 0 : w.distanceMiles, w.durationSeconds / 60);
        Intent i = new Intent(ctx, PickActivity.class)
                .putExtra("workout_id", w.id).putExtra("summary", summary)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); Log.i(TAG, w.id + " picker opened"); }
        catch (Exception e) { Log.w(TAG, "picker failed: " + e); }
    }

    /** Runner the picker saved for this workout (PickActivity's three locations), or null. */
    private String readPick(String id) {
        String name = id.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
        File ext = ctx.getExternalFilesDir(null);
        File[] dirs = { PICK_DIR, ext, ctx.getFilesDir() };
        for (File d : dirs) {
            if (d == null) continue;
            File f = new File(d, name);
            if (!f.isFile()) continue;
            try {
                String u = new JSONObject(readAll(f)).optString("user", "").trim();
                if (!u.isEmpty()) return u;
            } catch (Exception e) { Log.w(TAG, "bad pick file " + f + ": " + e); }
        }
        return null;
    }

    private int post(String body, String token) {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(URL_WORKOUT).openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = in == null ? "" : readAll(in);
            if (resp.length() > 300) resp = resp.substring(0, 300);
            Log.i(TAG, "worker HTTP " + code + " " + resp.replace('\n', ' '));
            return code;
        } catch (Exception e) {
            Log.w(TAG, "post error: " + e);
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- token
    private File tokenFile() { return new File(ctx.getFilesDir(), "upload_token"); }

    private void importToken() {
        File src = new File(PICK_DIR, ".upload_token");
        if (!src.isFile()) return;
        long age = System.currentTimeMillis() - src.lastModified();
        try {
            String raw = readAll(src);
            String t = raw.replace("\uFEFF", "").replaceAll("[\\s\"']", "");
            if (t.matches("[0-9a-fA-F]{32,128}")) {
                writeAtomic(tokenFile(), t.toLowerCase(Locale.US));
                if (!src.delete()) Log.w(TAG, "could not delete " + src);
                Log.i(TAG, "upload token imported (" + t.length() + " hex chars); sdcard copy deleted");
                probed = false;
                return;
            }
            // Describe the problem without revealing the content.
            Log.w(TAG, "upload token file not usable yet: " + raw.length() + " chars, " + t.length()
                    + " after trimming, age " + (age / 1000) + "s");
        } catch (Exception e) {
            Log.w(TAG, "token import failed: " + e);
        }
        if (age > 120_000L && src.delete()) Log.w(TAG, "deleted unusable upload token file");
    }

    /** One authenticated GET /stats/aaron; logs only the HTTP status. */
    private void probe() {
        String token = readToken();
        if (token == null) return;
        probed = true;
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(URL_WORKOUT.replace("/workout", "/stats/aaron")).openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            Log.i(TAG, "worker auth check: HTTP " + c.getResponseCode());
        } catch (Exception e) {
            Log.w(TAG, "worker auth check failed: " + e);
            probed = false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readToken() {
        try { String t = readAll(tokenFile()).trim(); return t.isEmpty() ? null : t; }
        catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------- state
    private File stateFile() { return new File(ctx.getFilesDir(), "stats_state.json"); }

    private JSONObject loadState() {
        try { return new JSONObject(readAll(stateFile())); } catch (Exception e) { return new JSONObject(); }
    }

    private void saveState(JSONObject s, Set<String> sent, JSONObject opened) throws Exception {
        JSONArray a = new JSONArray();
        for (String id : sent) a.put(id);
        s.put("sent", a);
        s.put("picker_opened", opened);
        writeAtomic(stateFile(), s.toString(1));
    }

    private static Set<String> toSet(JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    // ---------------------------------------------------------------- io
    private static String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("UTF-8");
    }

    private static void writeAtomic(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes("UTF-8")); o.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed for " + f);
    }
}
