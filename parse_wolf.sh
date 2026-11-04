#!/bin/sh
# parse_wolf.sh: turn an iFit wolf log (/sdcard/.wolflogs/YYYY-MM-DD_logs.txt) into
# one JSON object per completed workout (one that reached WorkoutResults).
# Usage: parse_wolf.sh LOGFILE [YYYY-MM-DD]
#   The date comes from the second argument, else from a YYYY-MM-DD in the file name.
# Portable: POSIX sh + POSIX awk (tested with mawk, gawk --posix and BWK awk, which macOS ships).
set -eu
if [ $# -lt 1 ] || [ ! -r "$1" ]; then
  echo "usage: $0 LOGFILE [YYYY-MM-DD]" >&2; exit 2
fi
LOG=$1
DATE=${2:-}
if [ -z "$DATE" ]; then
  DATE=$(basename "$LOG" | sed -n 's/.*\([0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]\).*/\1/p')
fi
case $DATE in
  [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
  *) echo "parse_wolf.sh: cannot tell the date; pass it as the second argument (YYYY-MM-DD)" >&2; exit 2 ;;
esac

LC_ALL=C awk -v DATE="$DATE" '
function tsec(t,   a) { split(t, a, ":"); return a[1]*3600 + a[2]*60 + a[3] }
function hms(s,   h, m) { s = int(s + 0.5); s = s % 86400; h = int(s/3600); m = int((s%3600)/60); return sprintf("%02d:%02d:%02d", h, m, s%60) }
function num(v) { return (v == "" ? "null" : v + 0) }
function fmt(v, p) { return (v == "" ? "null" : sprintf("%." p "f", v)) }
# time of the current line, made monotonic across midnight
function now(t) { t = tsec($3) + dayoff; if (lastt != "" && t < lastt - 43200) { dayoff += 86400; t += 86400 } lastt = t; return t }
# Speed timeline: accumulate km/h x seconds while the state is Workout.
function speed_to(k, t) {
  if (state == "Workout" && seg_t != "") { kph_area += cur_kph * (t - seg_t); kph_time += t - seg_t }
  seg_t = t
  cur_kph = k + 0
  if ((state == "Workout" || state == "WarmUp") && cur_kph > max_kph) max_kph = cur_kph
}
function reset() {
  in_wo = 0; have_results = 0; stats_open = 0; nstats = 0; stat_seen_count = 0
  start_t = ""; end_t = ""; pause_total = 0; pause_t = ""; npauses = 0
  run_secs = ""; meters = ""; kph_area = 0; kph_time = 0; seg_t = ""; max_kph = 0
  split("", st_dur); split("", st_time); split("", st_val); split("", st_order)
}
function emit(   id, wall, active, dist_m, mi, avg_dist, avg_tl, j, k, raw, dsrc, asrc) {
  id = DATE "T" hms(start_t); gsub(":", "-", id)
  wall = end_t - start_t
  # Active seconds: prefer iFit RunningTime (excludes pauses), then STATS DURATION, then wall minus pauses.
  if (run_secs != "") { active = run_secs; asrc = "running_time" }
  else if (("distance" in st_dur) && st_dur["distance"] != "") { active = st_dur["distance"] / 1000; asrc = "stats_duration" }
  else { active = wall - pause_total; asrc = "wall_minus_pauses" }
  # Distance: STATS distance LastStat Value (meters), else last SetMeters.
  if (("distance" in st_val) && st_val["distance"] != "") { dist_m = st_val["distance"]; dsrc = "stats" }
  else if (meters != "") { dist_m = meters; dsrc = "setmeters" }
  else { dist_m = ""; dsrc = "none" }
  mi = (dist_m == "" ? "" : dist_m / 1609.344)
  avg_dist = (mi != "" && active > 0 ? mi / (active / 3600) : "")
  avg_tl = (kph_time > 0 ? (kph_area / kph_time) / 1.609344 : "")
  raw = ""
  for (j = 1; j <= nstats; j++) {
    k = st_order[j]
    raw = raw (j > 1 ? "," : "") "\"" k "\":{\"duration_ms\":" num(st_dur[k]) ",\"last_stat_time\":" num(st_time[k]) ",\"last_stat_value\":" num(st_val[k]) "}"
  }
  printf "{\"workout_id\":\"%s\",\"date\":\"%s\",\"start_time\":\"%s\",\"end_time\":\"%s\",", id, DATE, hms(start_t), hms(end_t)
  printf "\"duration_seconds\":%d,\"duration_source\":\"%s\",\"wall_seconds\":%d,\"paused_seconds\":%d,\"pauses\":%d,", active + 0.5, asrc, wall + 0.5, pause_total + 0.5, npauses
  printf "\"distance_meters\":%s,\"distance_source\":\"%s\",\"distance_miles\":%s,", num(dist_m), dsrc, fmt(mi, 3)
  printf "\"avg_mph\":%s,\"avg_mph_kph_events\":%s,\"max_mph\":%s,", fmt(avg_dist, 2), fmt(avg_tl, 2), (max_kph > 0 ? sprintf("%.2f", max_kph / 1.609344) : "null")
  printf "\"calories\":%s,\"stats\":{%s}}\n", (("calories" in st_val) && st_val["calories"] != "" ? sprintf("%.1f", st_val["calories"]) : "null"), raw
}
BEGIN { reset(); state = "Unknown"; cur_kph = 0; dayoff = 0; lastt = "" }
# Only timestamped log lines: "[thread] ticks HH:MM:SS.ffff [Level:Tag] message"
!/^\[[0-9]+\] [0-9]+ [0-9][0-9]:[0-9][0-9]:[0-9][0-9]/ { next }
{ t = now() }
# The STATS block ends with its xscore line: emit before anything else (even a new workout) is handled.
stats_open && nstats >= 1 && st_order[nstats] == "xscore" && !/\[Trace:STATS\]/ { stats_open = 0; emit(); reset() }
/\[Trace:FitPro\] Changed state from: / {
  from = $0; sub(/.*Changed state from: /, "", from); sub(/ to: .*/, "", from)
  to = $0; sub(/.* to: /, "", to); sub(/[ \r]+$/, "", to)
  speed_to(cur_kph, t)            # close the speed segment under the old state
  if (to == "Workout" && from != "Paused") { reset(); in_wo = 1; start_t = t; seg_t = t }
  if (in_wo && to == "Paused") { pause_t = t; npauses++ }
  if (in_wo && from == "Paused" && pause_t != "") { pause_total += t - pause_t; pause_t = "" }
  if (in_wo && to == "WorkoutResults") { end_t = t; have_results = 1; stats_open = 1 }
  if (in_wo && (to == "Idle" || to == "WarmUp") && !have_results) { reset() }   # abandoned without results
  state = to
  next
}
/\[Trace:FitnessConsole\] (Kph changed from|Clamped Kph from) / {
  v = $0; sub(/.* to /, "", v); sub(/\.?[ \r]*$/, "", v)
  speed_to(v, t); next
}
in_wo && !have_results && /\[Trace:Workout\] Set current time \(r\) to [0-9]+ from RunningTime / {
  v = $0; sub(/.* from RunningTime /, "", v); sub(/ .*/, "", v); run_secs = v + 0; next
}
in_wo && !have_results && /\[Trace:Workout\] SetMeters [0-9.]+/ {
  v = $0; sub(/.*SetMeters /, "", v); sub(/[ \r]*$/, "", v); meters = v + 0; next
}
# First STATS block after WorkoutResults. iFit prints the same block again later
# (whenever the results are reopened), so we stop at the first repeated type.
stats_open && /\[Trace:STATS\] TYPE - / {
  k = $0; sub(/.*TYPE - /, "", k); sub(/[ \t].*/, "", k)
  d = $0; sub(/.*DURATION - /, "", d); sub(/[ \t\r].*/, "", d)
  if (k == "pulse" && nstats > 0) { stats_open = 0; emit(); reset(); next }
  if (!(k in st_dur)) { st_order[++nstats] = k; st_dur[k] = d; st_time[k] = ""; st_val[k] = "" }
  curk = k; next
}
stats_open && /\[Trace:STATS\] Last Stat Time - / {
  a = $0; sub(/.*Last Stat Time - /, "", a); sub(/[ \t].*/, "", a)
  b = $0; sub(/.*LastStat Value - /, "", b); sub(/[ \t\r]*$/, "", b)
  if (curk != "" && st_time[curk] == "") { st_time[curk] = a; st_val[curk] = b }
  next
}
stats_open && nstats > 0 && /\[Trace:STATS\]/ { next }
END {
  if (stats_open) emit()   # results reached, but log ended mid block
  else if (in_wo && !have_results) printf "parse_wolf.sh: workout started %s %s has not finished yet (no WorkoutResults); skipped\n", DATE, hms(start_t) > "/dev/stderr"
}
' "$LOG"
