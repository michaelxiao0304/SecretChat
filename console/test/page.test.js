import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate as settle } from 'node:timers/promises';
import { parseHTML } from 'linkedom';

const requestId = '5045538d-ff4b-49e0-a37a-32d6afcb85b8';
const failedResponse = status => new Response('sensitive server response', {status, headers:{'X-Request-ID':requestId}});

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

  http.mock.mockImplementation(async () => failedResponse(500));
  refresh.click();
  await settle();
  assert.equal(alert.hidden, false);
  assert.equal(alert.textContent, '队列指标加载失败，请稍后刷新。 请求编号：' + requestId);
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

test('login displays the failed request ID and omits it on network failure', async t => {
  const html = await readFile(new URL('../src/login.html', import.meta.url), 'utf8');
  const {document, window} = parseHTML(html);
  globalThis.document = document;
  t.after(() => { delete globalThis.document; });
  const formData = Object.getOwnPropertyDescriptor(globalThis,'FormData');
  class EmptyFormData extends URLSearchParams { constructor() { super(); } }
  Object.defineProperty(globalThis,'FormData',{configurable:true,value:EmptyFormData});
  t.after(() => Object.defineProperty(globalThis,'FormData',formData));
  const http = t.mock.method(globalThis,'fetch',async url => url.endsWith('/session')
    ? Response.json({header:'X-CSRF-TOKEN',token:'csrf'}) : failedResponse(401));
  await import('../src/login.js');
  const submit = () => document.querySelector('form').dispatchEvent(new window.Event('submit',{cancelable:true}));
  submit();
  await settle();
  const alert = document.querySelector('[role=alert]');
  assert.equal(alert.textContent, '账号或密码错误，请重试。 请求编号：' + requestId);
  http.mock.mockImplementation(async () => { throw new TypeError('offline'); });
  submit();
  await settle();
  assert.equal(alert.textContent, '暂时无法登录，请重试。');
});

test('operations displays only a canonical request ID from a failing response', async t => {
  const html = await readFile(new URL('../src/index.html', import.meta.url), 'utf8');
  const {document, window} = parseHTML(html);
  Object.assign(globalThis,{document,window});
  t.after(() => { delete globalThis.document; delete globalThis.window; });
  const http = t.mock.method(globalThis,'fetch',async url => url.endsWith('/me')
    ? Response.json({username:'test',roles:['OPS']}) : failedResponse(503));
  await import('../src/operations.js');
  await settle();
  const alert = document.querySelector('#ops-error');
  assert.equal(alert.textContent,'邮件与推送状态加载失败，请刷新。 请求编号：' + requestId);
  for (const invalid of ['private-material', requestId + ', ' + requestId, '5045538dff4b49e0a37a32d6afcb85b8']) {
    http.mock.mockImplementation(async () => new Response(null,{status:503,headers:{'X-Request-ID':invalid}}));
    document.querySelector('[data-view=overview]').click();
    await settle();
    assert.equal(alert.textContent,'邮件与推送状态加载失败，请刷新。');
  }
});
