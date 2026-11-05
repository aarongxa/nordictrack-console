package com.aarong.whoran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.AppOpsManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

public class HomeButtonService extends Service {
    private static final String TAG = "whoran";
    private static final String CHANNEL = "home_button";
    private static final int NOTIF_ID = 56;

    private WindowManager wm;
    private View button;
    private WindowManager.LayoutParams lp;
    private StatsUploader stats;

    // ---- v1.8: live runner badge ----
    private TextView badge;
    private WindowManager.LayoutParams badgeLp;
    private boolean live;                          // iFit workout screen seen (sticky while our picker is up)
    private boolean simWorkout;                    // adb test: --es sim_workout start|end
    private static HomeButtonService instance;

    // ---- v1.9: bring iFit up after boot (eru used to) ----
    private static final String LAUNCHER = "com.ifit.launcher";
    private static final long[] BOOT_DELAYS_S = { 25, 35, 50, 70 };   // checks at about 25, 60, 110, 180 s
    private boolean bootLaunchStarted;
    private int bootAttempt;
    private final Runnable bootLaunch = new Runnable() {
        @Override public void run() { bootLaunchStep(); }
    };

    // ---- idle blackout ----
    static final long IDLE_MS = 120_000L;          // blackout after this long without a touch
    private static final long CHECK_MS = 5_000L;
    private static final String FOSSIFY = "org.fossify.home";
    private static final String IFIT = "com.ifit.standalone";
    private static final String[] NEVER = {
            "com.netflix.mediaclient", "com.crunchyroll.crunchyroid", "org.schabi.newpipe" };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View touchSensor;
    private View blackout;
    private long lastTouch = SystemClock.elapsedRealtime();
    private String fgPkg, fgCls;
    private long fgScannedTo;
    private final Runnable tick = new Runnable() {
        @Override public void run() { checkIdle(); handler.postDelayed(this, CHECK_MS); }
    };

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void start(Context c) { start(c, false); }

    /** From BootReceiver on BOOT_COMPLETED: also bring iFit up once the system settles (v1.9). */
    static void startBoot(Context c) {
        if (!canDraw(c)) { Log.w(TAG, "no overlay permission; home button not started"); return; }
        Intent i = new Intent(c, HomeButtonService.class).putExtra("boot_launch", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start home button: " + e);
        }
    }

    static void simulate(Context c, String what) {
        if (!canDraw(c)) return;
        Intent i = new Intent(c, HomeButtonService.class).putExtra("sim_workout", what);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "simulate failed: " + e);
        }
    }

    /** Badge text changed (runner picked). Called on the main thread from PickActivity. */
    static void refreshBadge(Context c) {
        HomeButtonService s = instance;
        if (s != null) s.updateBadge();
    }

    static void start(Context c, boolean checkNow) {
        if (!canDraw(c)) { Log.w(TAG, "no overlay permission; home button not started"); return; }
        Intent i = new Intent(c, HomeButtonService.class).putExtra("check_now", checkNow);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start home button: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
        if (!canDraw(this)) { stopSelf(); return; }
        restoreNavBar();                              // undo a hide left by a crash or reboot
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
        addButton();
        addBadge();
        addTouchSensor();
        handler.postDelayed(tick, CHECK_MS);
        stats = new StatsUploader(this);              // v1.4: treadmill stats upload, on its own thread
        stats.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("check_now", false) && stats != null) stats.checkNow();
        if (intent != null && intent.getBooleanExtra("boot_launch", false) && !bootLaunchStarted && wm != null) {
            bootLaunchStarted = true;
            bootAttempt = 0;
            Log.i(TAG, "boot launch: iFit check in " + BOOT_DELAYS_S[0] + "s");
            handler.postDelayed(bootLaunch, BOOT_DELAYS_S[0] * 1000L);
        }
        String sim = intent != null ? intent.getStringExtra("sim_workout") : null;
        if (sim != null && wm != null) {                       // adb test of the workout start path, no belt
            simWorkout = "start".equals(sim);
            Log.i(TAG, "simulated workout " + (simWorkout ? "start" : "end"));
            setLive(simWorkout, true);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(bootLaunch);
        if (stats != null) { stats.stop(); stats = null; }
        if (blackout != null) restoreNavBar();
        removeView(blackout); blackout = null;
        removeView(touchSensor); touchSensor = null;
        removeView(button); button = null;
        removeView(badge); badge = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Home button", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_revert)
                .setContentTitle("Home button on")
                .setOngoing(true)
                .build();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addButton() {
        final int size = dp(56);
        final SharedPreferences prefs = getSharedPreferences("home_button", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = clamp(prefs.getInt("x", dm.widthPixels - size - dp(12)), 0, dm.widthPixels - size);
        lp.y = clamp(prefs.getInt("y", dm.heightPixels / 2 - size / 2), 0, dm.heightPixels - size);

        button = new HomeDot(this);
        button.setAlpha(0.6f);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY; int startX, startY; boolean dragging;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        noteTouch();
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        startX = lp.x; startY = lp.y; dragging = false;
                        v.setAlpha(0.9f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                        if (dragging) {
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            lp.x = clamp(startX + (int) dx, 0, m.widthPixels - size);
                            lp.y = clamp(startY + (int) dy, 0, m.heightPixels - size);
                            wm.updateViewLayout(button, lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(0.6f);
                        if (dragging) prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        else goHome();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(0.6f);
                        return true;
                }
                return false;
            }
        });
        wm.addView(button, lp);
    }

    /** "Running: name" pill, top center by default, draggable; tap reopens the picker. */
    private void addBadge() {
        final SharedPreferences prefs = getSharedPreferences("runner_badge", MODE_PRIVATE);
        badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        badge.setSingleLine(true);
        badge.setPadding(dp(16), dp(6), dp(16), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(200, 20, 20, 24));
        bg.setStroke(dp(1), Color.argb(160, 255, 255, 255));
        bg.setCornerRadius(dp(18));
        badge.setBackground(bg);
        badge.setVisibility(View.GONE);
        badgeLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        badgeLp.gravity = Gravity.TOP | Gravity.LEFT;
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        badgeLp.x = clamp(prefs.getInt("x", dm.widthPixels / 2 - dp(90)), 0, dm.widthPixels - dp(60));
        badgeLp.y = clamp(prefs.getInt("y", dp(6)), 0, dm.heightPixels - dp(36));
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        badge.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY; int startX, startY; boolean dragging;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        noteTouch();
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        startX = badgeLp.x; startY = badgeLp.y; dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                        if (dragging) {
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            badgeLp.x = clamp(startX + (int) dx, 0, m.widthPixels - v.getWidth());
                            badgeLp.y = clamp(startY + (int) dy, 0, m.heightPixels - v.getHeight());
                            wm.updateViewLayout(badge, badgeLp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) prefs.edit().putInt("x", badgeLp.x).putInt("y", badgeLp.y).apply();
                        else openLivePicker();
                        return true;
                }
                return false;
            }
        });
        wm.addView(badge, badgeLp);
    }

    private void updateBadge() {
        if (badge == null) return;
        String name = live ? LiveRunner.current(this) : null;
        badge.setText("Running: " + (name == null || name.isEmpty() ? "tap to pick" : name));
        badge.setVisibility(live && blackout == null ? View.VISIBLE : View.GONE);
    }

    private void openLivePicker() {
        Intent i = new Intent(this, PickActivity.class).putExtra("live", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); Log.i(TAG, "live picker opened"); }
        catch (Exception e) { Log.w(TAG, "live picker failed: " + e); }
    }

    /** Workout screen appeared (true) or went away (false). Opens the picker on a new start. */
    private void setLive(boolean now, boolean sim) {
        if (now == live) { updateBadge(); return; }
        live = now;
        long t = System.currentTimeMillis();
        if (now) {
            boolean fresh = LiveRunner.begin(this, t, sim);
            String cur = LiveRunner.current(this);
            if (fresh || cur == null || cur.isEmpty()) openLivePicker();
        } else {
            LiveRunner.end(this, t);
        }
        updateBadge();
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void removeView(View v) {
        if (v != null && wm != null) { try { wm.removeView(v); } catch (Exception ignored) { } }
    }

    private void noteTouch() { lastTouch = SystemClock.elapsedRealtime(); }

    /** 1x1 invisible window that hears every touch on the screen as ACTION_OUTSIDE. */
    private void addTouchSensor() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        touchSensor = new View(this);
        touchSensor.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { noteTouch(); return false; }
        });
        wm.addView(touchSensor, p);
    }

    private void checkIdle() {
        if (blackout != null) return;
        if (!blackoutAllowed()) { noteTouch(); return; }   // idle clock only runs on allowed screens
        if (SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS) showBlackout();
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Latest MOVE_TO_FOREGROUND package/class; scans incrementally. */
    private void refreshForeground() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        long from = fgScannedTo > 0 ? fgScannedTo - 2_000L : now - 24L * 3600_000L;
        UsageEvents ev = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        long best = -1;
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && e.getTimeStamp() >= best) {
                best = e.getTimeStamp();
                fgPkg = e.getPackageName();
                fgCls = e.getClassName();
            }
        }
        fgScannedTo = now;
    }

    /** Package of the latest MOVE_TO_FOREGROUND since this boot, or null if none yet. */
    private String foregroundSinceBoot() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null || !hasUsageAccess()) return null;
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - SystemClock.elapsedRealtime(), now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null; long best = -1;
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && e.getTimeStamp() >= best) {
                best = e.getTimeStamp(); pkg = e.getPackageName();
            }
        }
        return pkg;
    }

    private void bootLaunchStep() {
        String fg = foregroundSinceBoot();
        if (IFIT.equals(fg)) { Log.i(TAG, "boot launch: iFit is in the foreground, done"); return; }
        boolean idleScreen = fg == null || LAUNCHER.equals(fg) || FOSSIFY.equals(fg)
                || "com.android.settings".equals(fg) || getPackageName().equals(fg);
        if (idleScreen) {
            Intent i = getPackageManager().getLaunchIntentForPackage(IFIT);
            if (i == null) { Log.w(TAG, "boot launch: no launch intent for " + IFIT); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                Log.i(TAG, "boot launch: started " + i.getComponent().flattenToShortString()
                        + " (attempt " + (bootAttempt + 1) + ", fg was " + fg + ")");
            } catch (Exception e) {
                Log.w(TAG, "boot launch failed: " + e);
            }
        } else {
            Log.i(TAG, "boot launch: fg is " + fg + "; leaving it (attempt " + (bootAttempt + 1) + ")");
        }
        bootAttempt++;
        if (bootAttempt < BOOT_DELAYS_S.length) handler.postDelayed(bootLaunch, BOOT_DELAYS_S[bootAttempt] * 1000L);
        else handler.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "boot launch: final check, fg=" + foregroundSinceBoot() + "; giving up");
            }
        }, 15_000L);
    }

    private boolean blackoutAllowed() {
        if (!hasUsageAccess()) return false;          // without usage access we can't tell; never black out
        refreshForeground();
        String pkg = fgPkg, cls = fgCls == null ? "" : fgCls;
        boolean inWorkout = IFIT.equals(pkg) && cls.contains("InWorkoutView");
        if (getPackageName().equals(pkg)) inWorkout = live;   // our picker over the workout keeps it live
        if (simWorkout) inWorkout = true;
        if (stats != null) stats.setInWorkout(inWorkout);
        setLive(inWorkout, simWorkout);
        if (live) return false;                               // never black out mid run
        if (pkg == null) return false;
        for (String n : NEVER) if (n.equals(pkg)) return false;
        if (FOSSIFY.equals(pkg)) return true;
        if (getPackageName().equals(pkg)) return true;
        if (IFIT.equals(pkg)) return !cls.contains("InWorkoutView");
        return false;
    }

    private void showBlackout() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.screenBrightness = 0.01f;
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) hideBlackout();
                return true;                          // swallow the wake tap
            }
        });
        try {
            wm.addView(v, p);
            blackout = v;
            hideNavBar();
            if (button != null) button.setVisibility(View.GONE);
            updateBadge();
            Log.i(TAG, "blackout on (fg=" + fgPkg + "/" + fgCls + ")");
        } catch (Exception ex) {
            Log.w(TAG, "blackout failed: " + ex);
        }
    }

    private void hideBlackout() {
        removeView(blackout);
        blackout = null;
        restoreNavBar();
        if (button != null) button.setVisibility(View.VISIBLE);
        updateBadge();
        noteTouch();
        Log.i(TAG, "blackout off");
    }

    // ---- nav bar hide via global policy_control (needs WRITE_SECURE_SETTINGS via pm grant) ----
    private static final String POLICY = "policy_control";
    private static final String PREF_SAVED = "saved_policy";      // original value while hidden
    private static final String NULL_MARK = "\u0000null";        // original was unset

    private SharedPreferences navPrefs() { return getSharedPreferences("nav_bar", MODE_PRIVATE); }

    private void hideNavBar() {
        try {
            SharedPreferences sp = navPrefs();
            if (!sp.contains(PREF_SAVED)) {                         // never overwrite a saved original
                String cur = Settings.Global.getString(getContentResolver(), POLICY);
                sp.edit().putString(PREF_SAVED, cur == null ? NULL_MARK : cur).commit();
            }
            try {
                String c = Settings.Secure.getString(getContentResolver(), "immersive_mode_confirmations");
                if (c == null || !c.contains("confirmed"))
                    Settings.Secure.putString(getContentResolver(), "immersive_mode_confirmations", "confirmed");
            } catch (Exception ex) { Log.w(TAG, "immersive confirm not set: " + ex); }
            Settings.Global.putString(getContentResolver(), POLICY, "immersive.full=*");
            Log.i(TAG, "nav bar hidden");
        } catch (SecurityException ex) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, nav bar stays: " + ex);
        } catch (Exception ex) {
            Log.w(TAG, "hideNavBar failed: " + ex);
        }
    }

    private void restoreNavBar() {
        SharedPreferences sp = navPrefs();
        if (!sp.contains(PREF_SAVED)) return;
        String orig = sp.getString(PREF_SAVED, NULL_MARK);
        try {
            Settings.Global.putString(getContentResolver(), POLICY, NULL_MARK.equals(orig) ? null : orig);
            sp.edit().remove(PREF_SAVED).commit();
            Log.i(TAG, "nav bar restored to " + orig);
        } catch (SecurityException ex) {
            Log.w(TAG, "restore denied: " + ex);
            sp.edit().remove(PREF_SAVED).commit();                // we could not have changed it either
        } catch (Exception ex) {
            Log.w(TAG, "restoreNavBar failed: " + ex);
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (Exception e) { Log.w(TAG, "home failed: " + e); }
    }

    /** A dark round button with a white house outline. */
    static class HomeDot extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path house = new Path();

        HomeDot(Context c) {
            super(c);
            fill.setColor(Color.rgb(20, 20, 24));
            ring.setColor(Color.argb(200, 255, 255, 255));
            ring.setStyle(Paint.Style.STROKE);
            glyph.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            glyph.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            float s = getResources().getDisplayMetrics().density;
            ring.setStrokeWidth(2 * s);
            glyph.setStrokeWidth(2.5f * s);
            c.drawCircle(w / 2, h / 2, r - s, fill);
            c.drawCircle(w / 2, h / 2, r - 2 * s, ring);
            float u = r * 0.42f, cx = w / 2, cy = h / 2 + u * 0.1f;
            house.reset();
            house.moveTo(cx - u, cy - u * 0.05f);
            house.lineTo(cx, cy - u * 0.95f);
            house.lineTo(cx + u, cy - u * 0.05f);
            house.moveTo(cx - u * 0.72f, cy - u * 0.3f);
            house.lineTo(cx - u * 0.72f, cy + u * 0.8f);
            house.lineTo(cx + u * 0.72f, cy + u * 0.8f);
            house.lineTo(cx + u * 0.72f, cy - u * 0.3f);
            c.drawPath(house, glyph);
        }
    }
}
