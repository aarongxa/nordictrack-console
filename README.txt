whoran 1.2 (com.aarong.whoran)  APK: whoran/whoran.apk  rebuild: whoran/build.sh
Install + grants (run on the Mac with ANDROID_SERIAL set):
  adb install -r whoran.apk
  adb shell pm grant com.aarong.whoran android.permission.WRITE_EXTERNAL_STORAGE
  adb shell appops set com.aarong.whoran SYSTEM_ALERT_WINDOW allow
  adb shell appops set com.aarong.whoran GET_USAGE_STATS allow
  adb shell am start -n com.aarong.whoran/.StartButtonActivity        # starts Home button + blackout (also arms BOOT_COMPLETED)
Picker:  adb shell am start -n com.aarong.whoran/.PickActivity --es workout_id 2026-11-03T20-38-22 [--es summary "1.0 mi, 20 min"]
Result:  adb pull /sdcard/Download/whoran/2026-11-03T20-38-22.json
Stop button/blackout: adb shell am start -n com.aarong.whoran/.StartButtonActivity --ez stop true
Undo all: adb uninstall com.aarong.whoran
Parser: ./parse_wolf.sh /path/2026-11-03_logs.txt   (or pass date as 2nd arg)  -> NDJSON on stdout
