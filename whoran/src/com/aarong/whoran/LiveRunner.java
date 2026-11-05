package com.aarong.whoran;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v1.8: the runner picked while a workout is in progress. One session per iFit in workout screen
 * (start/end in wall clock ms). The uploader matches a finished wolf log workout to a session that
 * started near it and uses that name, so the end of workout picker is skipped. Sessions without a
 * name, and adb simulated sessions, never match.
 */
final class LiveRunner {
    private static final String TAG = "whoran";
    private static final String PREFS = "live_runner", KEY = "sessions";
    private static final int KEEP = 10;
    private static final long RESUME_MS = 90 * 60_000L;       // a service restart mid run resumes the session
    private static final long EARLY_MS = 10 * 60_000L;        // session may start up to 10 min before the log does

    private LiveRunner() { }

    private static synchronized JSONArray load(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void save(Context c, JSONArray a) {
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, a.length() - KEEP); i < a.length(); i++) out.put(a.opt(i));
        prefs(c).edit().putString(KEY, out.toString()).commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject active(JSONArray a) {
        JSONObject last = a.length() > 0 ? a.optJSONObject(a.length() - 1) : null;
        return last != null && last.optLong("end", 0) == 0 ? last : null;
    }

    /** Workout screen appeared. Returns true for a new session, false when a recent open one resumes. */
    static synchronized boolean begin(Context c, long now, boolean sim) {
        JSONArray a = load(c);
        JSONObject s = active(a);
        if (s != null && now - s.optLong("start") < RESUME_MS && s.optBoolean("sim") == sim) return false;
        try {
            if (s != null) s.put("end", now);                   // close a stale one
            a.put(new JSONObject().put("start", now).put("end", 0).put("name", "").put("sim", sim));
        } catch (Exception ignored) { }
        save(c, a);
        Log.i(TAG, "live session started" + (sim ? " (simulated)" : ""));
        return true;
    }

    static synchronized void end(Context c, long now) {
        JSONArray a = load(c);
        JSONObject s = active(a);
        if (s == null) return;
        try { s.put("end", now); } catch (Exception ignored) { }
        save(c, a);
        Log.i(TAG, "live session ended, runner=" + s.optString("name"));
    }

    /** Name for the open session; false when no workout is in progress. */
    static synchronized boolean setName(Context c, String name) {
        JSONArray a = load(c);
        JSONObject s = active(a);
        if (s == null) return false;
        try { s.put("name", name).put("picked", System.currentTimeMillis()); } catch (Exception ignored) { }
        save(c, a);
        Log.i(TAG, "live runner set to " + name);
        return true;
    }

    /** Name of the open session, "" if not picked yet, null if no session is open. */
    static synchronized String current(Context c) {
        JSONObject s = active(load(c));
        return s == null ? null : s.optString("name", "");
    }

    /** Runner picked during the run that produced this workout, or null. */
    static synchronized String runnerFor(Context c, long workoutStartMs, long workoutEndMs) {
        JSONArray a = load(c);
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject s = a.optJSONObject(i);
            if (s == null || s.optBoolean("sim")) continue;
            String name = s.optString("name", "").trim();
            if (name.isEmpty()) continue;
            long start = s.optLong("start"), end = s.optLong("end", 0);
            if (start < workoutStartMs - EARLY_MS || start > workoutEndMs) continue;
            if (end != 0 && end < workoutStartMs) continue;
            return name;
        }
        return null;
    }

    /** Store the matched name as this workout's pick (files dir, same shape PickActivity writes). */
    static void savePick(Context c, String workoutId, String user) {
        try {
            String at = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
            JSONObject j = new JSONObject().put("workout_id", workoutId).put("user", user)
                    .put("picked_at", at).put("source", "live");
            File f = new File(c.getFilesDir(), workoutId.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            FileOutputStream out = new FileOutputStream(f);
            try { out.write((j.toString() + "\n").getBytes("UTF-8")); } finally { out.close(); }
        } catch (Exception e) {
            Log.w(TAG, "live pick save failed: " + e);
        }
    }
}
