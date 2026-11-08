const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { DatabaseSync } = require('node:sqlite');

const root = path.resolve('../test_run/e2e');
const profiles = path.join(root, 'browser-profiles');
const profile = path.join(profiles, 'test.default');
fs.mkdirSync(profile, { recursive: true });
for (const suffix of ['', '-wal', '-shm']) fs.rmSync(path.join(root, 'geminibridge.db' + suffix), { force: true });
const file = path.join(profile, 'cookies.sqlite');
const database = new DatabaseSync(file);
database.exec('CREATE TABLE IF NOT EXISTS moz_cookies(name TEXT,value TEXT,host TEXT,expiry INTEGER,originAttributes TEXT,lastAccessed INTEGER); DELETE FROM moz_cookies');
const insert = database.prepare('INSERT INTO moz_cookies VALUES(?,?,?,?,?,?)');
insert.run('__Secure-1PSID', 'fixture-session', '.google.com', Math.floor(Date.now() / 1000) + 3600, '', 1);
insert.run('__Secure-1PSIDTS', 'fixture-timestamp', '.google.com', Math.floor(Date.now() / 1000) + 3600, '', 1);
database.close();
const profileId = crypto.createHash('sha256').update(fs.realpathSync(file)).digest('hex').slice(0, 20);
fs.writeFileSync(path.join(root, 'browser-session.json'), JSON.stringify({ enabled: true, profileId }), { mode: 0o600 });

const frame = (data, method) => {
  const text = JSON.stringify([['wrb.fr', method, JSON.stringify(data)]]);
  return `${Buffer.byteLength(text)}\n${text}\n`;
};
const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1:18083');
  if (request.method === 'GET' && url.pathname === '/app') {
    response.writeHead(200, { 'Content-Type': 'text/html' });
    response.end('<script>data={"SNlM0e":"fixture-csrf","cfb2h":"fixture-build","FdrFJe":"fixture-sid","TuX5cc":"en","oPEP7c":"tester@example.com"}</script>');
    return;
  }
  if (url.pathname.endsWith('/batchexecute')) {
    const model = Array(20).fill(null); model[0] = 'fixture-pro'; model[11] = '3.1 Pro'; model[17] = 3;
    const status = Array(18).fill(null); status[0] = 1; status[14] = 1000; status[15] = [model]; status[16] = []; status[17] = [];
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(")]}'\n\n" + frame(status, 'otAQ7b'));
    return;
  }
  if (url.pathname.endsWith('/StreamGenerate')) {
    let body = '';
    request.on('data', chunk => { body += chunk });
    request.on('end', () => {
      try {
        const selector = JSON.parse(request.headers['x-goog-ext-525001261-jspb']);
        if (selector[4] !== 'fixture-pro') throw new Error('Unexpected test model');
        const outer = JSON.parse(new URLSearchParams(body).get('f.req'));
        const inner = JSON.parse(outer[1]);
        const text = 'Fixture response\n\n' + inner[0][0];
        response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' }); response.write(")]}'\n\n");
        let end = 0;
        const timer = setInterval(() => {
          end = Math.min(end + 64, text.length);
          if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end++;
          const candidate = Array(9).fill(null); candidate[0] = 'candidate'; candidate[1] = [text.slice(0, end)]; candidate[8] = [end === text.length ? 2 : 1];
          const data = Array(5).fill(null); data[4] = [candidate]; response.write(frame(data, 'generate'));
          if (end === text.length) { clearInterval(timer); response.end() }
        }, 10);
        response.on('close', () => clearInterval(timer));
      } catch {
        response.writeHead(400); response.end('Invalid fixture request');
      }
    });
    return;
  }
  response.writeHead(404); response.end();
});
let child;
server.listen(18083, '127.0.0.1', () => {
  child = spawn('java', ['-jar', '../build/libs/geminibridge-1.0.0.jar', '--bridge.gemini-web-origin=http://127.0.0.1:18083'], {
    env: { ...process.env, GB_HOST: '127.0.0.1', GB_PORT: '18081', GB_DATA_DIR: root, GB_FIREFOX_ROOT: profiles }, stdio: 'inherit',
  });
  child.on('exit', code => server.close(() => process.exit(code || 0)));
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { child?.kill('SIGTERM'); server.close() });
