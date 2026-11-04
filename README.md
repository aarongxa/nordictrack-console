# nordictrack-console

Tools for a NordicTrack treadmill console (Android 9, API 28, 1024x600).

## whoran app (`com.aarong.whoran`, v1.3)

* **Who ran picker**: `PickActivity` asks Caitlin, Aaron, or Other (typed name) and writes `/sdcard/Download/whoran/<workout_id>.json`.
* **Floating Home button**: a draggable gray house overlay that opens the launcher. It starts again on its own at boot (`BOOT_COMPLETED`) and after app updates.
* **Idle blackout**: after 120 s with no touch (only on allowed screens, never during an iFit workout) a black overlay covers the screen. While it shows, the nav bar is hidden by setting global `policy_control` to `immersive.full=*`. The original value is saved first and put back on tap, on service start, and on service stop.

### Build

Needs a JDK in `jdk/` and an Android SDK in `sdk/` (platform android 28, build tools 34.0.0) beside `whoran/`. Neither is committed.

```
./whoran/build.sh
```

The script runs aapt2 link, javac (release 8), d8, zipalign and apksigner. It makes `whoran/debug.keystore` if missing (ignored by git) and writes `whoran/whoran.apk`. A prebuilt copy lives in `dist/` with its sha256.

### Install and grant

```
export ANDROID_SERIAL=<console-ip>:5555
adb install -r dist/whoran-1.6.apk
adb shell pm grant com.aarong.whoran android.permission.WRITE_EXTERNAL_STORAGE
adb shell appops set com.aarong.whoran SYSTEM_ALERT_WINDOW allow
adb shell appops set com.aarong.whoran GET_USAGE_STATS allow
adb shell pm grant com.aarong.whoran android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.aarong.whoran/.StartButtonActivity
```

Picker: `adb shell am start -n com.aarong.whoran/.PickActivity --es workout_id 2026-11-03T20-38-22 [--es summary "1.0 mi, 20 min"]`
Set wallpaper (1.6, logs under `whoran.wp`): `adb shell am start -n com.aarong.whoran/.StartButtonActivity --es wallpaper /sdcard/Pictures/treadmill_road.png`

Stop button and blackout: `adb shell am start -n com.aarong.whoran/.StartButtonActivity --ez stop true`

See `UNDO.md` for every console change and its undo.

## parse_wolf.sh

Turns an iFit wolf log (`/sdcard/.wolflogs/YYYY-MM-DD_logs.txt`) into NDJSON, one object per completed workout.

```
./parse_wolf.sh 2026-11-03_logs.txt [YYYY-MM-DD]
```

POSIX sh and awk; works with the awk macOS ships. `out_sample.ndjson` shows sample output.

## Known limits

* The backlight cannot be dimmed from software; the blackout shows black at full backlight (LCD, so black pixels do not burn in).
* A HID popup can appear after a reboot.
* Android shows a legacy permission review screen on first launch.
* The console cannot wake from real sleep by touch, so the blackout is an overlay instead of screen off.

## Stats pipeline (all on the console, whoran 1.5)

`StatsUploader` runs inside the always on `HomeButtonService` on its own thread. It checks once every 15 minutes,
and only when the treadmill is not in use: if iFit's in workout screen is in front, or the log shows a workout in
progress, the check is skipped until the next slot. When the in workout screen closes, one check runs a minute
later so the picker appears right after the workout. Each check:

1. Parses `/sdcard/.wolflogs/<today>_logs.txt` and yesterday's with `WolfParser`, a Java port of `parse_wolf.sh`
   (output is byte identical on the 11/03 log).
2. Opens the "Who ran?" picker once for each new finished workout (within 2 hours of its end).
3. After 30 minutes with no belt activity (3 hours if the log stopped mid workout), posts the workout plus the
   picked runner to the Worker. No pick after 11 minutes means `Unknown`, filed under Other.
   Workouts under 2 minutes are ignored. Sent ids are kept in the app's private `files/stats_state.json`;
   a failed post retries at the next check. The first run only records the workouts already in the logs.

Run a check now (also imports a token and probes the Worker):
`adb shell am start -n com.aarong.whoran/.StartButtonActivity --ez check true`

Upload token: the app reads `files/upload_token` (private storage). To provision, write the token to a mode 600
temp file on the Mac, `adb push` it to `/sdcard/Download/whoran/.upload_token.tmp`, delete the Mac file, then
`adb shell mv` it to `.upload_token` (so the app never sees a half written file). Do not use `adb exec-in`: on this
console it writes nothing. The app trims whitespace, quotes and a BOM, needs 32 to 128 hex characters, moves the
token into private storage, deletes the sdcard copy, and then makes one authenticated `GET /stats/aaron`, logging
only `worker auth check: HTTP <code>`. An unusable file is left alone for 2 minutes, then deleted. The token is never
logged or committed, and after provisioning it exists only on the console.

`worker/` is the `treadmill-stats` Worker on `https://treadmill-stats.aarongriffith06.workers.dev`. Every route needs
`Authorization: Bearer <token>`; the Worker keeps only the token's SHA 256 as the secret `UPLOAD_TOKEN_SHA256`.

* `POST /workout` stores `<runner>/<YYYY-MM-DD>T<HHMMSS>.json` in the private R2 bucket `treadmill-stats` and emails
  the runner from `treadmill@aarongxa.com` (addresses come from the Worker secrets `EMAIL_AARON` and `EMAIL_CAITLIN`; Other goes to `EMAIL_AARON`).
  Idempotent per `workout_id` through `_ids/<workout_id>.json`. `?dry_run=1` or `"dry_run": true` skips the email.
* `GET /stats/<aaron|caitlin|other>` lists workouts; `GET /stats/<runner>/<file>` returns one; `DELETE` removes one.
