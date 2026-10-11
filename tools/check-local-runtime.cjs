// Run: node tools/check-local-runtime.cjs (no phone, account or dependencies needed).
const assert = require('node:assert/strict');
const net = require('node:net');
const http = require('node:http');
const { PassThrough } = require('node:stream');
const { once } = require('node:events');
const { validateLaunch, firstLine, startProxy, validateTerminalCommand } = require('../app/src/main/assets/codeb-runtime.cjs');
(async () => {
  const options = { mode: 'standard', port: 23456, session: 'a'.repeat(32), tokenHash: 'b'.repeat(64) };
  assert.equal(validateLaunch(options), options);
  for (const change of [{ mode: 'sudo' }, { port: 0 }, { port: 2345.5 }, { session: '../escape' }, { tokenHash: 'short' }]) {
    assert.throws(() => validateLaunch({ ...options, ...change }));
  }
  const input = new PassThrough();
  const message = firstLine(input);
  input.write('{"ready":'); input.write('true}\n');
  assert.deepEqual(await message, { ready: true }); input.destroy();
  const oversized = new PassThrough(); const rejected = firstLine(oversized, 8);
  oversized.write('x'.repeat(9)); await assert.rejects(rejected); oversized.destroy();
  const echo = net.createServer(socket => socket.pipe(socket));
  echo.listen(0, '127.0.0.1'); await once(echo, 'listening');
  const proxy = await startProxy();
  try {
    const url = new URL(proxy.url);
    const terminal = (input, authorized = true, stdin = '') => new Promise((resolve, reject) => {
      const req = http.request({ hostname: url.hostname, port: url.port, path: '/termux', method: 'POST',
        auth: authorized ? `${url.username}:${url.password}` : 'invalid' }, res => {
        let body = ''; res.setEncoding('utf8'); res.on('data', chunk => body += chunk);
        res.on('end', () => resolve({ status: res.statusCode, body }));
      }); req.on('error', reject); req.end(JSON.stringify(input) + '\n' + stdin);
    });
    const command = { argv: [process.execPath, '-e', 'process.stdout.write(process.argv[1]);process.stderr.write("stderr");process.exitCode=7', 'literal $(echo unsafe)'], cwd: process.cwd() };
    assert.equal((await terminal(command, false)).status, 401);
    for (const invalid of [{ argv: [] }, { argv: ['a\0b'] }, { cwd: 'relative' }]) {
      assert.throws(() => validateTerminalCommand({ ...command, ...invalid }));
      assert.equal((await terminal({ ...command, ...invalid })).status, 400);
    }
    const result = await terminal(command);
    assert.equal(result.status, 200);
    const rows = result.body.trim().split('\n').map(line => JSON.parse(line));
    assert.equal(rows.map(row => row.stdout ? Buffer.from(row.stdout, 'base64').toString() : '').join(''), 'literal $(echo unsafe)');
    assert.equal(rows.map(row => row.stderr ? Buffer.from(row.stderr, 'base64').toString() : '').join(''), 'stderr');
    assert.equal(rows.at(-1).exitCode, 7);
    const echoed = await terminal({ argv: [process.execPath, '-e', 'process.stdin.pipe(process.stdout)'], cwd: process.cwd() }, true, 'stdin works\n');
    assert.equal(echoed.body.trim().split('\n').map(JSON.parse).filter(row => row.stdout).map(row => Buffer.from(row.stdout, 'base64').toString()).join(''), 'stdin works\n');
    for (const authorized of [false, true]) {
      const socket = net.connect(Number(url.port), '127.0.0.1');
      const response = once(socket, 'data');
      const auth = authorized ? `Proxy-Authorization: Basic ${Buffer.from(`${url.username}:${url.password}`).toString('base64')}\r\n` : '';
      socket.write(`CONNECT 127.0.0.1:${echo.address().port} HTTP/1.1\r\nHost: localhost\r\n${auth}\r\n`);
      assert.match((await response)[0].toString(), authorized ? /200 Connection Established/ : /407 Proxy Authentication Required/);
      if (authorized) { const reply = once(socket, 'data'); socket.write('verified'); assert.equal((await reply)[0].toString(), 'verified'); }
      socket.destroy();
    }
  } finally { proxy.close(); echo.close(); }
  console.log('Local runtime, handshake, authenticated proxy and Termux command execution checks passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
