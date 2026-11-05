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
 */
public class UsbOkService extends AccessibilityService {
    private static final String TAG = "whoran";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String OK_ID = "android:id/button1";
    private static final String DEVICE = "icon generic hid";
    private static final String APP = "ifit";
    private static final long DEBOUNCE_MS = 2000L;
    static final String LOG_FILE = "usb_ok.log";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClick;

    @Override
    protected void onServiceConnected() {
        log(this, "service connected");
        check("connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
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
