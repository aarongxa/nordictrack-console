package com.aarong.whoran;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Taps OK on exactly one dialog: SystemUI's USB permission prompt asking to let
 * iFIT access the "ICON Generic HID" motor board. Nothing else is ever clicked.
 * v1.9: also taps RETRY on the iFit launcher's blue arrow screen (com.ifit.launcher:id/retryButton),
 * at most MAX_RETRY times per boot, 20 s apart.
 */
public class UsbOkService extends AccessibilityService {
    private static final String TAG = "whoran";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String OK_ID = "android:id/button1";
    private static final String DEVICE = "icon generic hid";
    private static final String APP = "ifit";
    private static final long DEBOUNCE_MS = 2000L;
    static final String LOG_FILE = "usb_ok.log";
    private static final String LAUNCHER = "com.ifit.launcher";
    private static final String RETRY_ID = "com.ifit.launcher:id/retryButton";
    private static final long RETRY_DEBOUNCE_MS = 20_000L;
    private static final int MAX_RETRY = 5;
    private long lastRetry;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClick;

    @Override
    protected void onServiceConnected() {
        log(this, "service connected");
        check("connected");
        checkRetry("connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        if (LAUNCHER.contentEquals(e.getPackageName())) {
            checkRetry("event");
            handler.postDelayed(new Runnable() { public void run() { checkRetry("retry"); } }, 400);
            return;
        }
        if (!SYSTEMUI.contentEquals(e.getPackageName())) return;
        check("event");
        // the dialog content can land a moment after the window event
        handler.postDelayed(new Runnable() { public void run() { check("retry"); } }, 400);
    }

    private void check(String why) {
        if (SystemClock.elapsedRealtime() - lastClick < DEBOUNCE_MS) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (root.getPackageName() == null || !SYSTEMUI.contentEquals(root.getPackageName())) return;
            StringBuilder sb = new StringBuilder();
            collect(root, sb, 0);
            String text = sb.toString().toLowerCase(Locale.US);
            if (!text.contains(DEVICE) || !text.contains(APP)) return;
            List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId(OK_ID);
            if (ok == null || ok.isEmpty()) { log(this, "matched dialog but no " + OK_ID); return; }
            AccessibilityNodeInfo b = ok.get(0);
            boolean done = b.isEnabled() && b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (done) lastClick = SystemClock.elapsedRealtime();
            log(this, "clicked OK=" + done + " (" + why + ") text=\"" + sb.toString().trim().replace('\n', ' ') + "\"");
        } catch (Exception ex) {
            log(this, "error: " + ex);
        }
    }

    /** RETRY on the iFit launcher's blue arrow screen; capped per boot. */
    private void checkRetry(String why) {
        if (SystemClock.elapsedRealtime() - lastRetry < RETRY_DEBOUNCE_MS) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (root.getPackageName() == null || !LAUNCHER.contentEquals(root.getPackageName())) return;
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(RETRY_ID);
            if (found == null || found.isEmpty()) return;
            AccessibilityNodeInfo b = found.get(0);
            if (!b.isVisibleToUser() || !b.isEnabled() || b.getText() == null
                    || !"retry".equalsIgnoreCase(b.getText().toString().trim())) return;
            android.content.SharedPreferences sp = getSharedPreferences("usb_ok", MODE_PRIVATE);
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            int n = sp.getInt("retry_boot", -2) == boot ? sp.getInt("retry_taps", 0) : 0;
            if (n >= MAX_RETRY) {
                if (sp.getBoolean("retry_capped", false) == false) {
                    sp.edit().putBoolean("retry_capped", true).apply();
                    log(this, "RETRY cap of " + MAX_RETRY + " reached this boot; not tapping");
                }
                return;
            }
            lastRetry = SystemClock.elapsedRealtime();
            boolean done = b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            sp.edit().putInt("retry_boot", boot).putInt("retry_taps", n + 1).putBoolean("retry_capped", false).apply();
            log(this, "tapped RETRY=" + done + " (" + why + ") " + (n + 1) + "/" + MAX_RETRY + " this boot");
        } catch (Exception ex) {
            log(this, "retry error: " + ex);
        }
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 30) return;
        if (n.getText() != null) sb.append(n.getText()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    @Override
    public void onInterrupt() { }

    static void log(Context c, String msg) {
        Log.i(TAG, "UsbOk: " + msg);
        try {
            File f = new File(c.getFilesDir(), LOG_FILE);
            if (f.length() > 256 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    /** Adds this service to enabled_accessibility_services without dropping any others. */
    static void ensureEnabled(Context c) {
        try {
            String me = new ComponentName(c, UsbOkService.class).flattenToString();
            String shortMe = new ComponentName(c, UsbOkService.class).flattenToShortString();
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present = false;
            if (cur != null) for (String s : cur.split(":")) if (s.equals(me) || s.equals(shortMe)) present = true;
            if (!present) {
                String next = (cur == null || cur.trim().isEmpty()) ? me : cur + ":" + me;
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                log(c, "enabled accessibility service");
            }
            if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(TAG, "could not enable UsbOkService: " + e);
        }
    }
}
