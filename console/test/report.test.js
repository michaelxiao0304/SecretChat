import test from 'node:test';
import assert from 'node:assert/strict';
import { encryptedFixture } from './report-fixture.js';
import { decryptReport } from '../src/report-crypto.js';

test('reviewer decrypts only the report bound to its actors, ID and recipient', async () => {
  const {report,pem,evidence} = encryptedFixture();
  assert.deepEqual(await decryptReport(report, pem), evidence);
  for (const field of ['id','reporterAccountId','reportedAccountId','keyId']) {
    await assert.rejects(decryptReport({...report,[field]:crypto.randomUUID()}, pem));
  }
  await assert.rejects(decryptReport(report, encryptedFixture().pem));
});

test('keys used by the reviewer are non-extractable CryptoKeys', async t => {
  const keys = [];
  for (const operation of ['importKey','unwrapKey']) {
    const original = crypto.subtle[operation].bind(crypto.subtle);
    t.mock.method(crypto.subtle,operation,async (...args) => {
      const key = await original(...args);
      keys.push(key);
      return key;
    });
  }
  const {report,pem} = encryptedFixture();
  await decryptReport(report,pem);
  assert.deepEqual(keys.map(key => key.extractable),[false,false]);
});

test('malformed UTF-8, unexpected version, weak AES key and invalid PEM are rejected', async () => {
  const {report,pem} = encryptedFixture(Buffer.from([34,255,34]));
  await assert.rejects(decryptReport(report,pem));
  await assert.rejects(decryptReport({...report,version:2},pem),/未知举报版本/);
  await assert.rejects(decryptReport(report,'invalid'),/请选择 PKCS#8/);
  const weak = encryptedFixture(undefined,128);
  await assert.rejects(decryptReport(weak.report,weak.pem),/无效的举报密钥/);
});

test('browser without WebCrypto gets an actionable HTTPS message', async t => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis,'crypto');
  Object.defineProperty(globalThis,'crypto',{value:undefined,configurable:true});
  t.after(() => Object.defineProperty(globalThis,'crypto',descriptor));
  await assert.rejects(decryptReport({},''),/HTTPS/);
});
