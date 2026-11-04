# Console changes and how to undo them

Run on the Mac with `export ANDROID_SERIAL=<console-ip>:5555`, then plain `adb`.

| Change | Undo |
|---|---|
| Removed iFit eru (`com.ifit.eru`) for user 0 | `adb shell pm install-existing --user 0 com.ifit.eru` |
| Nav bar always shown (`policy_control` set to `immersive.status=*`) | `adb shell settings put global policy_control immersive.full=*` |
| Screen timeout and screensaver changed | `adb shell settings put system screen_off_timeout -1; adb shell settings put secure screensaver_enabled 1` |
| Assistive Menu Tool installed and enabled as accessibility service | `adb shell settings put secure enabled_accessibility_services 'com.ifit.glassos_service/com.ifit.glassos_appnavigation_service.service.AccessibilityServiceImpl'; adb uninstall com.joaohouto.assistivemenutool` |
| `user_setup_complete` set to 1 (fixes Home) | `adb shell settings put secure user_setup_complete 0` |
| whoran app installed | `adb uninstall com.aarong.whoran` |
| Brightness test (already reverted) | `adb shell settings put system screen_brightness 255` |
| whoran v1.3 hides nav bar during blackout (restored on tap) | `adb shell settings put global policy_control immersive.status=*` |
| whoran v1.3 sets `immersive_mode_confirmations` to confirmed | `adb shell settings delete secure immersive_mode_confirmations` |

# Stats pipeline (Cloudflare and console) and how to undo it

| Change | Undo |
|---|---|
| R2 bucket `treadmill-stats` (private, r2.dev off) | Empty it, then delete: dashboard R2, or `npx wrangler r2 bucket delete treadmill-stats` |
| Worker `treadmill-stats` on `treadmill-stats.aarongriffith06.workers.dev` (R2 binding, `send_email` binding, secret `UPLOAD_TOKEN_SHA256`) | `npx wrangler delete treadmill-stats` (or dashboard, Workers, treadmill-stats, Settings, Delete) |
| Email Sending onboarded for `aarongxa.com` (Aaron, in the dashboard; adds `cf-bounce` records and DMARC) | Dashboard, Email Service, Email Sending, `aarongxa.com`, remove domain |
| whoran 1.4 and 1.5 (INTERNET, the stats uploader; 1.5 checks every 15 minutes when not in use) | `adb uninstall com.aarong.whoran`, then reinstall 1.3 with the README steps and `dist/whoran-1.3.apk` (or `adb install -r -d dist/whoran-1.4.apk` to go back one version) |
| Upload token on the console (`/data/data/com.aarong.whoran/files/upload_token`) | Removed by the uninstall; or kill it server side by setting `UPLOAD_TOKEN_SHA256` to a new value |
| One workout in R2 | `curl -X DELETE -H "Authorization: Bearer <token>" https://treadmill-stats.aarongriffith06.workers.dev/stats/<runner>/<file>` |

Removed on 11/04: the Mac LaunchAgent `com.aarong.treadmill-stats` (never loaded), `~/treadmill-apks/treadmill_upload.sh`,
and the old Keychain item `treadmill-stats-upload` (its token no longer works). Also removed on 11/04, after the
console imported the token: the staging Keychain item `treadmill-console-token`. The token now lives only on the
console; to use the API from elsewhere, make a new token and set `UPLOAD_TOKEN_SHA256` to its hash, then provision
the console again with the README steps.
