// CoachCompass prototype server. No dependencies: `node server.js`, then point the phones at this laptop's IP.
// Merges anonymous per-coach counts from phones on the train and serves the platform view + station board.
const http = require('http');
const os = require('os');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT) || 8080;
const BASE = [58, 88, 84, 63, 38, 29]; // demo story loads for coaches 1–6
const FRESH_MS = 20000;
const APPROACH = 150, BOARDING = 20;

let cycleStart = Date.now();
const reports = {};   // coach -> { phoneId: { devices, people, load, t } }
const overrides = {}; // coach -> load, set from the board's operator controls

function phase() {
  const t = Math.floor((Date.now() - cycleStart) / 1000) % (APPROACH + BOARDING);
  return t < APPROACH ? { phase: 'approaching', etaSec: APPROACH - t } : { phase: 'boarding', etaSec: 0 };
}

function coaches() {
  const now = Date.now();
  return BASE.map((base, i) => {
    const n = i + 1;
    const fresh = Object.values(reports[n] || {}).filter(r => now - r.t < FRESH_MS);
    if (fresh.length) {
      const load = Math.round(fresh.reduce((s, r) => s + r.load, 0) / fresh.length);
      return { n, load, source: 'live', phones: fresh.length };
    }
    if (overrides[n] != null) return { n, load: overrides[n], source: 'operator', phones: 0 };
    return { n, load: Math.round(base + 1.5 * Math.sin(now / 7000 + n)), source: 'model', phones: 0 };
  });
}

function state() {
  return {
    train: 'TR-14', line: 'Purple', towards: 'Whitefield', station: 'Trinity', platform: 2,
    ...phase(), coaches: coaches(), updated: Date.now(),
  };
}

function lanIps() {
  return Object.values(os.networkInterfaces()).flat().filter(a => a && a.family === 'IPv4' && !a.internal).map(a => a.address);
}

function body(req) {
  return new Promise(resolve => {
    let d = '';
    req.on('data', c => (d += c));
    req.on('end', () => { try { resolve(JSON.parse(d || '{}')); } catch { resolve({}); } });
  });
}

function send(res, code, obj, type = 'application/json') {
  res.writeHead(code, { 'Content-Type': type, 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type' });
  res.end(type === 'application/json' ? JSON.stringify(obj) : obj);
}

http.createServer(async (req, res) => {
  const url = req.url.split('?')[0];
  if (req.method === 'OPTIONS') return send(res, 204, '');
  if (url === '/api/state') return send(res, 200, state());
  if (url === '/api/info') return send(res, 200, { ips: lanIps(), port: PORT });
  if (url === '/api/report' && req.method === 'POST') {
    const r = await body(req);
    const n = Number(r.coach);
    if (!(n >= 1 && n <= 6)) return send(res, 400, { error: 'coach must be 1–6' });
    (reports[n] ||= {})[String(r.phoneId || req.socket.remoteAddress)] = {
      devices: Number(r.devices) || 0, people: Number(r.people) || 0,
      load: Math.max(0, Math.min(100, Number(r.load) || 0)), t: Date.now(),
    };
    console.log(`report  coach ${n}  ${r.devices} devices → ~${r.people} people → ${r.load}%  (phone ${r.phoneId})`);
    return send(res, 200, { ok: true });
  }
  if (url === '/api/override' && req.method === 'POST') {
    const r = await body(req);
    if (r.coach >= 1 && r.coach <= 6) r.load == null ? delete overrides[r.coach] : (overrides[r.coach] = Math.max(0, Math.min(100, Number(r.load))));
    return send(res, 200, { ok: true });
  }
  if (url === '/api/reset' && req.method === 'POST') {
    for (const k in overrides) delete overrides[k];
    for (const k in reports) delete reports[k];
    cycleStart = Date.now();
    return send(res, 200, { ok: true });
  }
  if (url === '/' || url === '/board') return send(res, 200, fs.readFileSync(path.join(__dirname, 'board.html')), 'text/html; charset=utf-8');
  send(res, 404, { error: 'not found' });
}).listen(PORT, '0.0.0.0', () => {
  const ips = lanIps();
  console.log('\nCoachCompass server running');
  console.log(`  Station board:  http://localhost:${PORT}`);
  for (const ip of ips) console.log(`  Phones: type    ${ip}   in the app's server box`);
  console.log('  (Phones and laptop must be on the same Wi-Fi or hotspot.)\n');
});
