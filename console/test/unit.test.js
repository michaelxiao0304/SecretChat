import test from 'node:test';
import assert from 'node:assert/strict';
import { formatWait, request, submitForm } from '../src/client.js';

test('wait distinguishes an empty queue, a fresh envelope, and elapsed time', () => {
  assert.equal(formatWait(null), '—');
  assert.equal(formatWait(0), '0 秒');
  assert.equal(formatWait(59), '59 秒');
  assert.equal(formatWait(60), '1 分 0 秒');
  assert.equal(formatWait(127), '2 分 7 秒');
});

test('API reads relative to console under any deployment prefix', async () => {
  const http = async (url, options) => {
    assert.equal(url, '../admin-api/queue');
    assert.equal(options.credentials, 'same-origin');
    return Response.json({pendingCount: 3});
  };
  assert.deepEqual(await request('queue', {}, http), {pendingCount: 3});
});

test('form submission fetches a current CSRF token and posts encoded fields', async () => {
  const calls = [];
  const http = async (url, options) => {
    calls.push(url);
    if (url.endsWith('/session')) return Response.json({token:'fresh', header:'X-CSRF-TOKEN'});
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['X-CSRF-TOKEN'], 'fresh');
    assert.equal(options.body.get('password'), 'words & spaces');
    return new Response(null, {status:204});
  };
  assert.equal(await submitForm('login', {password:'words & spaces'}, http), undefined);
  assert.deepEqual(calls, ['../admin-api/session', '../admin-api/login']);
});

test('HTTP failures retain status and do not render an error response as data', async () => {
  for (const status of [401, 403, 500]) {
    await assert.rejects(request('queue', {}, async () => new Response(null,{status})), {status, message:'HTTP ' + status});
  }
});
