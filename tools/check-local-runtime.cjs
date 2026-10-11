// Run: node tools/check-local-runtime.cjs (no phone, account or dependencies needed).
const assert = require('node:assert/strict');
const net = require('node:net');
const { PassThrough } = require('node:stream');
const { once } = require('node:events');
const { validateLaunch, firstLine, startProxy } = require('../app/src/main/assets/codeb-runtime.cjs');
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
  console.log('Local runtime validation, fragmented handshake and authenticated network proxy passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
