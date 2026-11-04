// treadmill-stats Worker
//   POST   /workout                 store one workout JSON, email the runner (bearer token)
//   GET    /stats/<runner>          list that runner's workouts (bearer token)
//   GET    /stats/<runner>/<file>   one workout JSON (bearer token)
//   DELETE /stats/<runner>/<file>   remove one workout and its id marker (bearer token)
// R2 layout: <runner>/<YYYY-MM-DD>T<HHMMSS>.json, plus _ids/<workout_id>.json markers
// that make POST /workout idempotent (a workout id is emailed at most once).

const FROM = { email: "treadmill@aarongxa.com", name: "Treadmill" };
// Recipients are Worker secrets, not code: EMAIL_AARON, EMAIL_CAITLIN (Other goes to EMAIL_AARON).
const recipient = (env, key) => (key === "caitlin" ? env.EMAIL_CAITLIN : env.EMAIL_AARON) || null;
const ID_RE = /^(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})$/;
const FILE_RE = /^\d{4}-\d{2}-\d{2}T\d{6}\.json$/;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const json = (body, status = 200) =>
  new Response(JSON.stringify(body, null, 2), { status, headers: { "content-type": "application/json" } });

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function authorized(request, env) {
  const want = (env.UPLOAD_TOKEN_SHA256 || "").trim().toLowerCase();
  const m = (request.headers.get("authorization") || "").match(/^Bearer\s+(\S+)$/i);
  if (!want || !m) return false;
  const got = await sha256hex(m[1]);
  // Compare digests in constant time.
  let diff = got.length ^ want.length;
  for (let i = 0; i < Math.min(got.length, want.length); i++) diff |= got.charCodeAt(i) ^ want.charCodeAt(i);
  return diff === 0;
}

function runnerOf(raw) {
  const name = String(raw || "").trim().slice(0, 60);
  const k = name.toLowerCase();
  if (k === "aaron" || k === "caitlin") return { key: k, name: k === "aaron" ? "Aaron" : "Caitlin" };
  return { key: "other", name: name || "Unknown" };
}

const num = (v) => (typeof v === "number" && isFinite(v) ? v : null);
const noHyphen = (s) => String(s).replace(/[-\u2010-\u2015]/g, " ");

function when(date, time) {
  const [, mo, d] = date.split("-").map(Number);
  let [h, mi] = time.split(":").map(Number);
  const ap = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  return `${MONTHS[mo - 1]} ${d} at ${h}:${String(mi).padStart(2, "0")} ${ap}`;
}

function dur(sec) {
  sec = Math.max(0, Math.round(sec));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const parts = [];
  if (h) parts.push(`${h} hr`);
  if (h || m) parts.push(`${m} min`);
  parts.push(`${s} sec`);
  return parts.join(" ");
}

function summaryRows(w, runner) {
  const rows = [["Runner", runner.key === "other" ? `${runner.name} (Other)` : runner.name]];
  rows.push(["Started", when(w.date, w.start_time)]);
  if (w.end_time) rows.push(["Finished", when(w.date, w.end_time).split(" at ")[1]]);
  rows.push(["Duration", dur(w.duration_seconds)]);
  if (num(w.paused_seconds) && w.paused_seconds > 0) rows.push(["Paused", dur(w.paused_seconds)]);
  const mi = num(w.distance_miles);
  if (mi !== null) rows.push(["Distance", `${mi.toFixed(2)} mi`]);
  const avg = num(w.avg_mph) ?? num(w.avg_mph_kph_events);
  if (avg !== null) rows.push(["Average speed", `${avg.toFixed(1)} mph`]);
  if (num(w.max_mph) !== null) rows.push(["Top speed", `${w.max_mph.toFixed(1)} mph`]);
  if (mi && mi > 0 && w.duration_seconds > 0) {
    const p = Math.round(w.duration_seconds / mi); // seconds per mile
    if (p < 6000) rows.push(["Pace", `${Math.floor(p / 60)}:${String(p % 60).padStart(2, "0")} per mi`]);
  }
  const inc = num(w.avg_incline) ?? num(w.stats?.grade?.last_stat_value);
  if (inc !== null) rows.push(["Incline", `${inc.toFixed(1)} percent` + (num(w.max_incline) !== null ? `, max ${w.max_incline.toFixed(1)}` : "")]);
  if (num(w.calories) !== null) rows.push(["Calories", `${Math.round(w.calories)}`]);
  return rows.map(([k, v]) => [k, noHyphen(v)]);
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function buildEmail(w, runner) {
  const rows = summaryRows(w, runner);
  const subject = noHyphen(`Treadmill run, ${runner.name}, ${when(w.date, w.start_time)}`);
  const text = rows.map(([k, v]) => `${k}: ${v}`).join("\n") + "\n\nSent by the treadmill stats pipeline.\n";
  const html =
    `<html><body><h2>Treadmill run</h2><table cellpadding="6">` +
    rows.map(([k, v]) => `<tr><td><b>${esc(k)}</b></td><td>${esc(v)}</td></tr>`).join("") +
    `</table><p><small>Sent by the treadmill stats pipeline.</small></p></body></html>`;
  return { subject, text, html };
}

function validate(w) {
  if (!w || typeof w !== "object" || Array.isArray(w)) return "body must be a JSON object";
  if (typeof w.workout_id !== "string" || !ID_RE.test(w.workout_id)) return "workout_id must look like 2026-11-04T19-12-05";
  if (w.date !== w.workout_id.slice(0, 10)) return "date must match workout_id";
  if (typeof w.start_time !== "string" || !/^\d{2}:\d{2}:\d{2}$/.test(w.start_time)) return "start_time must be HH:MM:SS";
  if (w.end_time != null && !/^\d{2}:\d{2}:\d{2}$/.test(w.end_time)) return "end_time must be HH:MM:SS";
  if (num(w.duration_seconds) === null || w.duration_seconds < 0) return "duration_seconds must be a number";
  if (typeof w.runner !== "string" || !w.runner.trim()) return "runner is required";
  return null;
}

async function postWorkout(request, env, url) {
  const raw = await request.text();
  if (raw.length > 65536) return json({ ok: false, error: "body too large" }, 413);
  let w;
  try { w = JSON.parse(raw); } catch { return json({ ok: false, error: "invalid JSON" }, 400); }
  const bad = validate(w);
  if (bad) return json({ ok: false, error: bad }, 400);

  const dryRun = url.searchParams.get("dry_run") === "1" || w.dry_run === true;
  const runner = runnerOf(w.runner);
  const [, day, hh, mm, ss] = w.workout_id.match(ID_RE);
  const key = `${runner.key}/${day}T${hh}${mm}${ss}.json`;
  const markerKey = `_ids/${w.workout_id}.json`;

  const prev = await env.STATS.get(markerKey);
  const marker = prev ? await prev.json() : null;
  if (marker && marker.emailed) return json({ ok: true, duplicate: true, key: marker.key, emailed: true });

  const record = { ...w, runner: runner.name, runner_key: runner.key, received_at: new Date().toISOString() };
  delete record.dry_run;
  // A runner change on resend moves the object.
  if (marker && marker.key !== key) await env.STATS.delete(marker.key);
  await env.STATS.put(key, JSON.stringify(record, null, 2), {
    httpMetadata: { contentType: "application/json" },
    customMetadata: { workout_id: w.workout_id, runner: runner.name, miles: String(num(w.distance_miles) ?? ""), seconds: String(w.duration_seconds) },
  });

  const to = recipient(env, runner.key);
  const mail = buildEmail(w, runner);
  let emailed = false, messageId = null, emailError = null;
  if (!dryRun) {
    if (!env.EMAIL) emailError = "send_email binding not configured";
    else if (!to) emailError = `no recipient secret for ${runner.key}`;
    else {
      try {
        const r = await env.EMAIL.send({ from: FROM, to, subject: mail.subject, text: mail.text, html: mail.html });
        emailed = true; messageId = r && r.messageId;
      } catch (e) { emailError = `${e.code || "error"}: ${e.message}`; }
    }
  }
  await env.STATS.put(markerKey, JSON.stringify({ key, emailed, messageId, dry_run: dryRun, at: record.received_at }), {
    httpMetadata: { contentType: "application/json" },
  });
  const body = { ok: !emailError, stored: true, key, dry_run: dryRun, emailed, to: dryRun || emailed ? to : undefined, subject: mail.subject, messageId };
  if (dryRun) body.preview = mail.text;
  if (emailError) { body.error = emailError; return json(body, 502); }
  return json(body);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (!(await authorized(request, env))) return json({ ok: false, error: "unauthorized" }, 401);

    if (request.method === "POST" && url.pathname === "/workout") return postWorkout(request, env, url);

    if (parts[0] === "stats" && parts.length >= 2) {
      const runner = parts[1].toLowerCase();
      if (!/^(aaron|caitlin|other)$/.test(runner)) return json({ ok: false, error: "runner must be aaron, caitlin or other" }, 404);
      if (parts.length === 2 && request.method === "GET") {
        const items = [];
        let cursor;
        do {
          const l = await env.STATS.list({ prefix: `${runner}/`, cursor, include: ["customMetadata"] });
          for (const o of l.objects) items.push({ file: o.key.slice(runner.length + 1), size: o.size, uploaded: o.uploaded, ...o.customMetadata });
          cursor = l.truncated ? l.cursor : undefined;
        } while (cursor);
        items.sort((a, b) => (a.file < b.file ? 1 : -1));
        return json({ ok: true, runner, count: items.length, workouts: items });
      }
      if (parts.length === 3 && FILE_RE.test(parts[2])) {
        const key = `${runner}/${parts[2]}`;
        if (request.method === "GET") {
          const o = await env.STATS.get(key);
          if (!o) return json({ ok: false, error: "not found" }, 404);
          return new Response(o.body, { headers: { "content-type": "application/json" } });
        }
        if (request.method === "DELETE") {
          const o = await env.STATS.get(key);
          if (!o) return json({ ok: false, error: "not found" }, 404);
          const rec = await o.json();
          await env.STATS.delete([key, `_ids/${rec.workout_id}.json`]);
          return json({ ok: true, deleted: key });
        }
      }
    }
    return json({ ok: false, error: "not found" }, 404);
  },
};
