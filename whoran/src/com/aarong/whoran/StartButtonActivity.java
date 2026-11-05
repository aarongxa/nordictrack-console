package com.aarong.whoran;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Launcher entry and adb entry: starts (or with --ez stop true, stops) the floating Home button.
 *  With --es wallpaper /abs/path.png it only sets the system and lock wallpaper, then finishes.
 *  With --ez check true it also runs a stats check now (token import, Worker probe, log scan).
 *  With --es sim_workout start|end it fakes the iFit workout screen for the runner picker and badge test. */
public class StartButtonActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent i = getIntent();
        String wp = i != null ? i.getStringExtra("wallpaper") : null;
        if (wp != null) {
            setWallpaper(wp);
            finish();
            return;
        }
        if (i != null && i.getBooleanExtra("stop", false)) {
            stopService(new Intent(this, HomeButtonService.class));
        } else if (!HomeButtonService.canDraw(this)) {
            Toast.makeText(this, "Overlay permission missing: adb shell appops set com.aarong.whoran SYSTEM_ALERT_WINDOW allow",
                    Toast.LENGTH_LONG).show();
        } else {
            String sim = i != null ? i.getStringExtra("sim_workout") : null;
            if (sim != null) HomeButtonService.simulate(this, sim);    // v1.8 test: start|end, no belt
            else HomeButtonService.start(this, i != null && i.getBooleanExtra("check", false));
        }
        finish();
    }

    private void setWallpaper(String path) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) { Log.e("whoran.wp", "decode failed: " + path); return; }
            WallpaperManager.getInstance(this).setBitmap(bmp, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            Log.i("whoran.wp", "wallpaper set from " + path + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ")");
        } catch (Throwable t) {
            Log.e("whoran.wp", "wallpaper failed: " + path, t);
        }
    }
}
