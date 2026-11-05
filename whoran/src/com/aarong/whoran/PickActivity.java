package com.aarong.whoran;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PickActivity extends Activity {
    private static final String TAG = "whoran";
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long LIVE_TIMEOUT_MS = 3 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = new Runnable() {
        @Override public void run() { if (live) finish(); else finishWith("Unknown"); }
    };

    private String workoutId;
    private boolean done;
    private boolean live;          // v1.8: picked while running (badge or workout start), no workout id yet
    private TextView title;
    private TextView subtitle;
    private LinearLayout otherRow;
    private EditText otherField;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(buildUi());
        HomeButtonService.start(this);
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A new workout arrived while the old prompt was still open: close the old one as Unknown.
        if (!done && !live && workoutId != null) writeJson(workoutId, "Unknown");
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        live = intent != null && intent.getBooleanExtra("live", false);
        if (live) {
            String cur = LiveRunner.current(this);
            title.setText("Who's running?");
            subtitle.setText(cur != null && !cur.isEmpty()
                    ? "Now: " + cur + ". Tap the Running badge anytime to change it."
                    : "Tap the Running badge anytime to change it.");
            workoutId = null;
            done = false;
            otherRow.setVisibility(View.GONE);
            otherField.setText("");
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, LIVE_TIMEOUT_MS);
            return;
        }
        title.setText("Who ran?");
        String id = intent != null ? intent.getStringExtra("workout_id") : null;
        if (id == null || id.trim().isEmpty()) {
            id = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(new Date());
        }
        workoutId = id.trim();
        done = false;
        String summary = intent != null ? intent.getStringExtra("summary") : null;
        subtitle.setText(summary != null && !summary.isEmpty() ? summary : "Workout " + workoutId);
        otherRow.setVisibility(View.GONE);
        otherField.setText("");
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(timeout);
        if (!done && !live && workoutId != null && isFinishing()) writeJson(workoutId, "Unknown");
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { if (live) finish(); else finishWith("Unknown"); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button bigButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(170), 1f);
        lp.setMargins(dp(14), 0, dp(14), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        title = new TextView(this);
        title.setText("Who ran?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(Color.rgb(160, 160, 170));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(28));
        root.addView(subtitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Button aaron = bigButton("Aaron", Color.rgb(37, 99, 235));
        Button caitlin = bigButton("Caitlin", Color.rgb(219, 39, 119));
        Button other = bigButton("Other", Color.rgb(75, 85, 99));
        row.addView(aaron);
        row.addView(caitlin);
        row.addView(other);
        root.addView(row);

        otherRow = new LinearLayout(this);
        otherRow.setOrientation(LinearLayout.HORIZONTAL);
        otherRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams orlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        orlp.setMargins(dp(14), dp(24), dp(14), 0);
        otherRow.setLayoutParams(orlp);
        otherRow.setVisibility(View.GONE);

        otherField = new EditText(this);
        otherField.setHint("Type a name");
        otherField.setHintTextColor(Color.rgb(120, 120, 130));
        otherField.setTextColor(Color.WHITE);
        otherField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        otherField.setSingleLine(true);
        otherField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        otherField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(Color.rgb(38, 38, 46));
        fbg.setCornerRadius(dp(12));
        otherField.setBackground(fbg);
        otherField.setPadding(dp(20), dp(14), dp(20), dp(14));
        otherField.setLayoutParams(new LinearLayout.LayoutParams(0, dp(90), 1f));
        otherRow.addView(otherField);

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Color.rgb(22, 163, 74));
        sbg.setCornerRadius(dp(12));
        save.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(200), dp(90));
        slp.setMargins(dp(20), 0, 0, 0);
        save.setLayoutParams(slp);
        otherRow.addView(save);
        root.addView(otherRow);

        aaron.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWith("Aaron"); }
        });
        caitlin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWith("Caitlin"); }
        });
        other.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                otherRow.setVisibility(View.VISIBLE);
                otherField.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(otherField, InputMethodManager.SHOW_IMPLICIT);
                // Reset the idle timer while someone is typing a name.
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, TIMEOUT_MS);
            }
        });
        View.OnClickListener saveOther = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = otherField.getText().toString().trim();
                if (name.isEmpty()) { otherField.setError("Type a name"); return; }
                if (name.length() > 60) name = name.substring(0, 60);
                finishWith(name);
            }
        };
        save.setOnClickListener(saveOther);
        otherField.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveOther.onClick(tv); return true; }
            return false;
        });
        return root;
    }

    private void finishWith(String user) {
        if (done) return;
        if (live) {
            done = true;
            LiveRunner.setName(this, user);
            HomeButtonService.refreshBadge(this);
            handler.removeCallbacks(timeout);
            finish();
            return;
        }
        writeJson(workoutId, user);
        handler.removeCallbacks(timeout);
        finish();
    }

    private static String esc(String s) {
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
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private void writeJson(String id, String user) {
        done = true;
        String pickedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        String json = "{\"workout_id\":\"" + esc(id) + "\",\"user\":\"" + esc(user)
                + "\",\"picked_at\":\"" + pickedAt + "\"}\n";
        String fileName = id.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
        File primary = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "whoran");
        if (!write(primary, fileName, json)) {
            File fallback = getExternalFilesDir(null);
            if (fallback == null || !write(fallback, fileName, json)) {
                write(getFilesDir(), fileName, json);
            }
        }
    }

    private boolean write(File dir, String name, String json) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = new File(dir, name + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            File dest = new File(dir, name);
            if (!tmp.renameTo(dest)) { tmp.delete(); return false; }
            Log.i(TAG, "wrote " + dest.getAbsolutePath() + " " + json.trim());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write failed in " + dir + ": " + e);
            return false;
        }
    }
}
