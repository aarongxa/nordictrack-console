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
| whoran 1.7 (11/04): adds `UsbOkService`, an accessibility service that taps OK only on the SystemUI dialog asking to let iFIT access ICON Generic HID; BootReceiver re-adds it to the enabled list at every boot and install | Don't uninstall (that deletes the upload token, which lives only on the console) and don't use `-d` (this user build blocks downgrades). Rebuild the 1.6 source (`git show 116334d:whoran/...`) with a versionCode above the installed one (10 now that 1.8 is 9) and `adb install -r` it, then run the next row |
| `UsbOkService` appended to `enabled_accessibility_services` (`accessibility_enabled` was already 1) | `adb shell settings put secure enabled_accessibility_services 'com.ifit.glassos_service/com.ifit.glassos_appnavigation_service.service.AccessibilityServiceImpl:com.joaohouto.assistivemenutool/com.joaohouto.assistivemenutool.AssistiveMenuAccessibilityService'` (the value before 1.7). On its own this only lasts until the next boot while 1.7 is installed |
| whoran 1.8 (11/04, no reboot): at the iFit workout screen opens a "Who's running?" picker, shows a draggable "Running: name" badge (top center) that reopens it, and the uploader uses that name for the workout instead of the end prompt. State: SharedPreferences `live_runner` and `runner_badge`, plus `files/<workout>.json` picks with `"source":"live"`. adb test hook: `am start -n com.aarong.whoran/.StartButtonActivity --es sim_workout start` or `end` (simulated sessions never match a workout) | Same rule as 1.7: no uninstall, no `-d`. Back to 1.7: rebuild commit 1.7 source (`git show b9c4a4a:whoran/...`) as versionCode 10 and `adb install -r`. If the badge is ever stuck on screen: `adb shell am start -n com.aarong.whoran/.StartButtonActivity --es sim_workout end` |
| whoran 1.9 (11/04, one reboot 7:43 PM ET): on BOOT_COMPLETED only, the service checks the foreground at about 25, 60, 110 and 180 s after boot and, if the screen is the iFit launcher, Fossify, Settings or whoran, starts iFit's own launch intent (`com.ifit.standalone/crc648e51133e922cfbf3.PermissionsActivity`). UsbOkService also watches `com.ifit.launcher` and taps `com.ifit.launcher:id/retryButton` (text RETRY), 20 s apart, at most 5 per boot (count in SharedPreferences `usb_ok`) | No uninstall, no `-d`. Back to 1.8: rebuild the whoran/ source from commit 49056ca as versionCode 11 and `adb install -r`. That alone also drops `com.ifit.launcher` from the service's packages; no setting to restore |

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
