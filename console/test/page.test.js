import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate as settle } from 'node:timers/promises';
import { parseHTML } from 'linkedom';

test('dashboard renders counts, prevents duplicate refresh, recovers from errors and handles expiry', async t => {
  const html = await readFile(new URL('../src/index.html', import.meta.url), 'utf8');
  const {document} = parseHTML(html);
  const destinations = [];
  const redirects = {replace: url => destinations.push(url)};
  Object.assign(globalThis, {document, location:redirects});
  t.after(() => { delete globalThis.document; delete globalThis.location; });
  const http = t.mock.method(globalThis, 'fetch');
  let finish;
  http.mock.mockImplementation(() => new Promise(resolve => { finish = resolve; }));

  await import('../src/dashboard.js');
  const element = id => document.querySelector(id);
  const refresh = element('#refresh');
  const alert = element('[role=alert]');
  assert.equal(refresh.disabled, true);
  finish(Response.json({pendingCount:1200, oldestWaitSeconds:65}));
  await settle();
  assert.equal(refresh.disabled, false);
  assert.equal(element('#pending').textContent, '1,200');
  assert.equal(element('#wait').textContent, '1 分 5 秒');
  assert.match(element('#status').textContent, /^更新于 .+/);
  assert.equal(alert.hidden, true);

  http.mock.mockImplementation(async () => new Response(null, {status:500}));
  refresh.click();
  await settle();
  assert.equal(alert.hidden, false);
  assert.equal(element('#pending').textContent, '—');
  assert.equal(element('#wait').textContent, '—');
  assert.equal(element('#status').textContent, '');

  http.mock.mockImplementation(async () => Response.json({pendingCount:0, oldestWaitSeconds:null}));
  refresh.click();
  assert.equal(alert.hidden, true);
  await settle();
  assert.equal(element('#pending').textContent, '0');
  assert.equal(element('#wait').textContent, '—');

  http.mock.mockImplementation(async () => new Response(null, {status:401}));
  refresh.click();
  await settle();
  assert.deepEqual(destinations, ['login.html']);

  const logout = element('#logout');
  logout.click();
  await settle();
  assert.equal(logout.disabled, false);
  assert.equal(alert.hidden, false);
  assert.match(alert.textContent, /退出失败/);
  http.mock.mockImplementation(async url => url.endsWith('/session')
    ? Response.json({token:'token', header:'X-CSRF-TOKEN'}) : new Response(null, {status:204}));
  logout.click();
  await settle();
  assert.deepEqual(destinations, ['login.html', 'login.html']);
});
